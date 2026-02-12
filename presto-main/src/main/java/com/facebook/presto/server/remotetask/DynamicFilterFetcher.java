/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.server.remotetask;

import com.facebook.airlift.http.client.HttpClient;
import com.facebook.airlift.http.client.Request;
import com.facebook.airlift.json.JsonCodec;
import com.facebook.airlift.log.Logger;
import com.facebook.airlift.units.Duration;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.execution.TaskId;
import com.facebook.presto.execution.scheduler.DynamicFilterService;
import com.facebook.presto.execution.scheduler.DynamicFilterStats;
import com.facebook.presto.server.RequestErrorTracker;
import com.facebook.presto.server.SimpleHttpResponseCallback;
import com.facebook.presto.server.SimpleHttpResponseHandler;
import com.facebook.presto.server.smile.BaseResponse;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.QueryId;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.netty.channel.EventLoop;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.facebook.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static com.facebook.airlift.http.client.Request.Builder.prepareDelete;
import static com.facebook.airlift.http.client.Request.Builder.prepareGet;
import static com.facebook.airlift.http.client.StatusResponseHandler.createStatusResponseHandler;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_MAX_WAIT;
import static com.facebook.presto.server.RequestErrorTracker.taskRequestErrorTracker;
import static com.facebook.presto.server.smile.AdaptingJsonResponseHandler.createAdaptingJsonResponseHandler;
import static com.facebook.presto.spi.StandardErrorCode.REMOTE_TASK_ERROR;
import static com.google.common.base.Verify.verify;
import static java.util.Objects.requireNonNull;

/**
 * Long-polls {@code GET /v1/task/{taskId}/dynamicFilters?since=N} to collect
 * dynamic filters from build-side workers. Sends {@code DELETE ?through=N}
 * after processing to free worker memory.
 */
public class DynamicFilterFetcher
        implements SimpleHttpResponseCallback<DynamicFilterResponse>
{
    private static final Logger log = Logger.get(DynamicFilterFetcher.class);

    private final TaskId taskId;
    private final QueryId queryId;
    private final URI taskLocation;
    private final HttpClient httpClient;
    private final EventLoop taskEventLoop;
    private final JsonCodec<DynamicFilterResponse> filterCodec;
    private final RemoteTaskStats stats;
    private final DynamicFilterStats dynamicFilterStats;
    private final RequestErrorTracker errorTracker;
    private final DynamicFilterService dynamicFilterService;

    private final AtomicLong lastFetchedVersion = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean operatorCompletionHandled = new AtomicBoolean(false);
    private final Duration maxWait;

    private volatile ListenableFuture<BaseResponse<DynamicFilterResponse>> future;
    private long currentRequestStartNanos;

    public DynamicFilterFetcher(
            TaskId taskId,
            URI taskLocation,
            HttpClient httpClient,
            EventLoop taskEventLoop,
            Duration maxErrorDuration,
            Duration maxWait,
            RemoteTaskStats stats,
            JsonCodec<DynamicFilterResponse> filterCodec,
            DynamicFilterService dynamicFilterService,
            DynamicFilterStats dynamicFilterStats)
    {
        this.taskId = requireNonNull(taskId, "taskId is null");
        this.queryId = taskId.getQueryId();
        this.taskLocation = requireNonNull(taskLocation, "taskLocation is null");
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.taskEventLoop = requireNonNull(taskEventLoop, "taskEventLoop is null");
        this.maxWait = requireNonNull(maxWait, "maxWait is null");
        this.stats = requireNonNull(stats, "stats is null");
        this.dynamicFilterStats = requireNonNull(dynamicFilterStats, "dynamicFilterStats is null");
        this.filterCodec = requireNonNull(filterCodec, "filterCodec is null");
        this.dynamicFilterService = requireNonNull(dynamicFilterService, "dynamicFilterService is null");
        this.errorTracker = taskRequestErrorTracker(
                taskId,
                taskLocation,
                maxErrorDuration,
                taskEventLoop,
                "fetching dynamic filters for task");
    }

    /** Call once after construction. */
    public void start()
    {
        taskEventLoop.execute(this::sendFetchRequest);
    }

    private void sendFetchRequest()
    {
        verify(taskEventLoop.inEventLoop());

        if (!running.get()) {
            return;
        }

        if (future != null && !future.isDone()) {
            return;
        }

        ListenableFuture<?> errorRateLimit = errorTracker.acquireRequestPermit();
        if (!errorRateLimit.isDone()) {
            errorRateLimit.addListener(this::sendFetchRequest, taskEventLoop);
            return;
        }

        long currentVersion = lastFetchedVersion.get();
        URI uri = uriBuilderFrom(taskLocation)
                .appendPath("dynamicFilters")
                .addParameter("since", String.valueOf(currentVersion))
                .build();

        Request request = prepareGet()
                .setUri(uri)
                .setHeader(PRESTO_MAX_WAIT, maxWait.toString())
                .build();

        errorTracker.startRequest();
        future = httpClient.executeAsync(request, createAdaptingJsonResponseHandler(filterCodec));
        currentRequestStartNanos = System.nanoTime();

        SimpleHttpResponseHandler<DynamicFilterResponse> callback = new SimpleHttpResponseHandler<>(
                this,
                request.getUri(),
                stats.getHttpResponseStats(),
                REMOTE_TASK_ERROR);

        Futures.addCallback(future, callback, taskEventLoop);
    }

    @Override
    public void success(DynamicFilterResponse response)
    {
        verify(taskEventLoop.inEventLoop());

        long requestDurationNanos = System.nanoTime() - currentRequestStartNanos;
        long roundTripMs = requestDurationNanos / 1_000_000;
        stats.infoRoundTripMillis(roundTripMs);
        dynamicFilterStats.recordFilterFetchRoundTripMillis(roundTripMs);
        errorTracker.requestSucceeded();

        long responseVersion = response.getVersion();
        lastFetchedVersion.updateAndGet(current -> Math.max(current, responseVersion));

        Map<String, TupleDomain<String>> filters = response.getFilters();
        dynamicFilterStats.getFilterFetchSuccess().update(1);

        if (!filters.isEmpty()) {
            dynamicFilterStats.getFiltersCollected().update(filters.size());

            for (Map.Entry<String, TupleDomain<String>> entry : filters.entrySet()) {
                String filterId = entry.getKey();
                TupleDomain<String> filterDomain = entry.getValue();
                dynamicFilterService.getFilter(queryId, filterId).ifPresent(filter ->
                        filter.addPartitionByFilterId(filterDomain));
            }

            sendDeleteRequest(responseVersion);

            // Filters already contributed; skip none() on completion
            if (response.isOperatorCompleted() && operatorCompletionHandled.compareAndSet(false, true)) {
                stop();
                return;
            }
        }
        else if (response.isOperatorCompleted() && operatorCompletionHandled.compareAndSet(false, true)) {
            // none() counts toward expectedPartitions; scope to this task's filters (multi-join safety)
            for (String filterId : response.getCompletedFilterIds()) {
                dynamicFilterService.getFilter(queryId, filterId).ifPresent(filter ->
                        filter.addPartitionByFilterId(TupleDomain.none()));
            }
            stop();
            return;
        }

        scheduleNextPoll();
    }

    private void sendDeleteRequest(long throughVersion)
    {
        URI uri = uriBuilderFrom(taskLocation)
                .appendPath("dynamicFilters")
                .addParameter("through", String.valueOf(throughVersion))
                .build();
        Request request = prepareDelete()
                .setUri(uri)
                .build();
        httpClient.executeAsync(request, createStatusResponseHandler());
    }

    private void scheduleNextPoll()
    {
        if (!running.get()) {
            return;
        }

        taskEventLoop.execute(() -> {
            if (running.get()) {
                sendFetchRequest();
            }
        });
    }

    @Override
    public void failed(Throwable cause)
    {
        verify(taskEventLoop.inEventLoop());
        dynamicFilterStats.getFilterFetchFailure().update(1);

        try {
            errorTracker.requestFailed(cause);
        }
        catch (PrestoException e) {
            // Too many failures; query continues without DPP (filter times out to all())
            log.warn(e, "Giving up on dynamic filter fetch for task %s, stopping fetcher", taskId);
            stop();
            return;
        }

        scheduleNextPoll();
    }

    @Override
    public void fatal(Throwable cause)
    {
        verify(taskEventLoop.inEventLoop());
        // DPP is optional for correctness; do not fail the query
        log.warn(cause, "Fatal error fetching dynamic filters from task %s, stopping fetcher", taskId);
        dynamicFilterStats.getFilterFetchFailure().update(1);
        stop();
    }

    /**
     * Stop polling. Pending requests are allowed to complete so late-arriving filters
     * are still processed.
     */
    public void stop()
    {
        running.set(false);
    }

    /** Cancels pending HTTP requests and stops polling. */
    public void abort()
    {
        running.set(false);
        ListenableFuture<BaseResponse<DynamicFilterResponse>> pendingFuture = future;
        if (pendingFuture != null && !pendingFuture.isDone()) {
            pendingFuture.cancel(false);
        }
    }

    public long getLastFetchedVersion()
    {
        return lastFetchedVersion.get();
    }

    public TaskId getTaskId()
    {
        return taskId;
    }
}
