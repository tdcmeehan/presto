package com.facebook.presto.dispatcher;

import com.facebook.airlift.http.client.HttpClient;
import com.facebook.airlift.http.client.Request;
import com.facebook.airlift.http.client.StatusResponseHandler;
import com.facebook.airlift.json.JsonCodec;
import com.facebook.presto.Session;
import com.facebook.presto.execution.ExecutionFailureInfo;
import com.facebook.presto.execution.QueryInfo;
import com.facebook.presto.execution.QueryState;
import com.facebook.presto.execution.StateMachine;
import com.facebook.presto.metadata.SessionPropertyManager;
import com.facebook.presto.server.BasicQueryInfo;
import com.facebook.presto.spi.ErrorCode;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.QueryId;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.joda.time.DateTime;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.airlift.http.client.JsonResponseHandler.createJsonResponseHandler;
import static com.facebook.airlift.http.client.Request.Builder.prepareDelete;
import static com.facebook.airlift.http.client.Request.Builder.prepareGet;
import static com.facebook.airlift.http.client.Request.Builder.preparePost;
import static com.facebook.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static com.facebook.airlift.http.client.StatusResponseHandler.createStatusResponseHandler;
import static com.facebook.presto.execution.QueryState.FAILED;
import static com.facebook.presto.execution.QueryState.QUEUED;
import static com.facebook.presto.execution.QueryState.TERMINAL_QUERY_STATES;
import static com.facebook.presto.util.Failures.toFailure;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class RemoteDispatchQuery
        implements DispatchQuery
{
    private final QueryId queryId;
    private final String query;
    private final StateMachine<QueryState> queryState;
    @SuppressWarnings("UnstableApiUsage")
    private final HttpClient httpClient;
    private final SessionPropertyManager sessionPropertyManager;
    private final AtomicReference<QueryInfo> remoteQueryInfo = new AtomicReference<>(null);
    private final SettableFuture<?> dispatchedFuture = SettableFuture.create();
    private final ScheduledExecutorService scheduledExecutorService;
    private final AtomicInteger token = new AtomicInteger(0);
    private final String slug;
    private JsonCodec<QueryInfo> queryInfoJsonCodec;

    CoordinatorLocation coordinatorLocation = (uriInfo, xForwardedProto) -> {
        String scheme = isNullOrEmpty(xForwardedProto) ? uriInfo.getRequestUri().getScheme() : xForwardedProto;
        return uriInfo.getRequestUriBuilder()
                .host("localhost")
                .port(8081)
                .scheme(scheme)
                .replacePath("")
                .replaceQuery("")
                .build();
    };

    public RemoteDispatchQuery(
            QueryId queryId,
            String query,
            String slug,
            JsonCodec<QueryInfo> queryInfoJsonCodec,
            HttpClient httpClient,
            SessionPropertyManager sessionPropertyManager,
            Executor executor,
            ScheduledExecutorService scheduledExecutorService)
    {
        this.queryId = queryId;
        this.query = query;
        this.slug = slug;
        this.queryInfoJsonCodec = queryInfoJsonCodec;
        this.queryState = new StateMachine<>("query " + queryId, executor, QUEUED, TERMINAL_QUERY_STATES);
        this.httpClient = httpClient;
        this.sessionPropertyManager = sessionPropertyManager;
        this.scheduledExecutorService = scheduledExecutorService;
        scheduledExecutorService.scheduleAtFixedRate(this::run, 0, 50, MILLISECONDS);
    }

    private void run()
    {
        try {
            QueryInfo queryInfo = getQueryInfo();
            queryState.set(queryInfo.getState());
            if (queryInfo.getState().isDone() && !dispatchedFuture.isDone()) {
                dispatchedFuture.set(null);
            }
            remoteQueryInfo.set(queryInfo);
        }
        catch (Throwable t) {
            // Ignore...
//            t.printStackTrace();
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private QueryInfo getQueryInfo()
    {
        Request request = prepareGet().setUri(URI.create("http://localhost:8081/v1/query/" + queryId)).build();
        return httpClient.execute(request, createJsonResponseHandler(queryInfoJsonCodec));
    }

    @Override
    public void recordHeartbeat()
    {
        // no op
    }

    @Override
    public ListenableFuture<?> getDispatchedFuture()
    {
        return dispatchedFuture;
    }

    @Override
    public DispatchInfo getDispatchInfo()
    {
        // observe submitted before getting the state, to ensure a failed query stat is visible
        boolean dispatched = dispatchedFuture.isDone();
        QueryInfo queryInfo = remoteQueryInfo.get();

        if (queryInfo == null) {
            return DispatchInfo.queued(Duration.valueOf("1s"), Duration.valueOf("1s"));
        }

        if (queryInfo.getState() == FAILED) {
            requireNonNull(queryInfo.getFailureInfo(), "Failure info was null");
            ExecutionFailureInfo failureInfo = toFailure(new PrestoException(queryInfo::getErrorCode, queryInfo.getFailureInfo().getMessage()));
            return DispatchInfo.failed(failureInfo, queryInfo.getQueryStats().getElapsedTime(), queryInfo.getQueryStats().getQueuedTime());
        }
        if (dispatched) {
            return DispatchInfo.dispatched(coordinatorLocation, queryInfo.getQueryStats().getElapsedTime(), queryInfo.getQueryStats().getQueuedTime());
        }
        return DispatchInfo.queued(queryInfo.getQueryStats().getElapsedTime(), queryInfo.getQueryStats().getQueuedTime());
    }

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void cancel()
    {
        Request request = prepareDelete().setUri(URI.create("http://localhost:8081/v1/query/" + queryId)).build();
        httpClient.execute(request, createStatusResponseHandler());
    }

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void startWaitingForResources()
    {
        Request request = preparePost()
                .setUri(URI.create(format("http://localhost:8081/v1/statement/executing/%s?slug=%s", queryId, slug)))
                .addHeader("X-Presto-User", "tdm")
                .setBodyGenerator(createStaticBodyGenerator(query.getBytes()))
                .build();
        StatusResponseHandler.StatusResponse response = httpClient.execute(request, createStatusResponseHandler());
        System.out.println(response);
    }

    @Override
    public void addStateChangeListener(StateMachine.StateChangeListener<QueryState> stateChangeListener)
    {
        queryState.addStateChangeListener(stateChangeListener);
    }

    @Override
    public DataSize getUserMemoryReservation()
    {
        if (remoteQueryInfo.get() == null) {
            return DataSize.succinctBytes(0);
        }
        return remoteQueryInfo.get().getQueryStats().getUserMemoryReservation();
    }

    @Override
    public DataSize getTotalMemoryReservation()
    {
        if (remoteQueryInfo.get() == null) {
            return DataSize.succinctBytes(0);
        }
        return remoteQueryInfo.get().getQueryStats().getTotalMemoryReservation();
    }

    @Override
    public Duration getTotalCpuTime()
    {
        if (remoteQueryInfo.get() == null) {
            return Duration.succinctDuration(0, SECONDS);
        }
        return remoteQueryInfo.get().getQueryStats().getTotalCpuTime();
    }

    @Override
    public BasicQueryInfo getBasicQueryInfo()
    {
        return new BasicQueryInfo(getQueryInfo());
    }

    @Override
    public Optional<ErrorCode> getErrorCode()
    {
        return Optional.empty();
    }

    @Override
    public QueryId getQueryId()
    {
        return queryId;
    }

    @Override
    public boolean isDone()
    {
        if (remoteQueryInfo.get() == null) {
            return false;
        }
        return remoteQueryInfo.get().getState().isDone();
    }

    @Override
    public Session getSession()
    {
        return remoteQueryInfo.get().getSession().toSession(sessionPropertyManager);
    }

    @Override
    public DateTime getCreateTime()
    {
        return remoteQueryInfo.get().getQueryStats().getCreateTime();
    }

    @Override
    public Optional<DateTime> getExecutionStartTime()
    {
        return Optional.of(remoteQueryInfo.get().getQueryStats().getExecutionStartTime());
    }

    @Override
    public DateTime getLastHeartbeat()
    {
        return remoteQueryInfo.get().getQueryStats().getLastHeartbeat();
    }

    @Override
    public Optional<DateTime> getEndTime()
    {
        return Optional.ofNullable(remoteQueryInfo.get().getQueryStats().getEndTime());
    }

    @Override
    public int getRunningTaskCount()
    {
        return remoteQueryInfo.get().getQueryStats().getRunningTasks();
    }

    @Override
    public void fail(Throwable cause)
    {
        // TODO
        cancel();
    }

    @Override
    public void pruneInfo()
    {
        // NO OP
    }
}
