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
package com.facebook.presto.execution.scheduler;

import com.facebook.airlift.units.Duration;
import com.facebook.presto.common.RuntimeStats;
import com.facebook.presto.common.block.Block;
import com.facebook.presto.common.predicate.Domain;
import com.facebook.presto.common.predicate.Range;
import com.facebook.presto.common.predicate.SortedRangeSet;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.common.predicate.ValueSet;
import com.facebook.presto.spi.connector.DynamicFilter;
import com.google.common.collect.ImmutableMap;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_ARRIVAL_SPREAD_NANOS;  // DIAGNOSTIC(DPP-PARTITIONED-WAIT)
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_COLLECTION_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_COORDINATOR_FALLBACK_TO_RANGE;
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_DEFICIT_AT_TIMEOUT;  // DIAGNOSTIC(DPP-PARTITIONED-WAIT)
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_DOMAIN_RANGE_COUNT;
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_EXPECTED_PARTITIONS;
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_EXTENSION_COUNT;  // DIAGNOSTIC(DPP-PARTITIONED-WAIT)
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_PARTITIONS_AT_TIMEOUT;  // DIAGNOSTIC(DPP-PARTITIONED-WAIT)
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_PARTITIONS_RECEIVED;
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_SHORT_CIRCUITED;
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_TIMED_OUT;
import static com.facebook.presto.common.RuntimeMetricName.DYNAMIC_FILTER_TIME_SINCE_LAST_ARRIVAL_AT_TIMEOUT_NANOS;  // DIAGNOSTIC(DPP-PARTITIONED-WAIT)
import static com.facebook.presto.common.RuntimeUnit.NANO;
import static com.facebook.presto.common.RuntimeUnit.NONE;
import static com.facebook.presto.spi.connector.DynamicFilter.NOT_BLOCKED;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Verify.verify;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Coordinator-side per-join dynamic filter. Does NOT implement {@link DynamicFilter};
 * the SPI-facing wrapper is {@link TableScanDynamicFilter}.
 *
 * <p>Constraints are stored keyed by filter ID and translated to column names
 * at the boundary via {@link #getCurrentConstraintByColumnName()}. Each build-side
 * task contributes exactly one filter (its complete hash-table key set, or
 * {@link TupleDomain#none()} for an empty build); the merge fires once
 * {@link #expectedPartitions} contributions have arrived.
 */
@ThreadSafe
public class JoinDynamicFilter
{
    // Single-thread daemon scheduler shared across all JoinDynamicFilter instances.
    // Ticks are short (O(1) work under a per-filter monitor); a single thread is sufficient
    // because at most one tick per filter is pending at a time and ticks self-reschedule.
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "join-dynamic-filter-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private final String filterId;
    private final String columnName;
    private final Duration waitTimeout;
    private final int maxWaitExtensions;
    private final long maxSizeInBytes;
    private final DynamicFilterStats stats;
    private final RuntimeStats runtimeStats;
    private final boolean extendedMetrics;

    @GuardedBy("this")
    private final List<TupleDomain<String>> partitionsByFilterId = new ArrayList<>();
    private final CompletableFuture<TupleDomain<String>> constraintByFilterIdFuture;

    private final AtomicBoolean timeoutStarted = new AtomicBoolean(false);

    @GuardedBy("this")
    private int expectedPartitions;

    private volatile boolean fullyResolved;

    @GuardedBy("this")
    private TupleDomain<String> mergedConstraint;

    @GuardedBy("this")
    private Domain probeColumnDomain;

    @GuardedBy("this")
    private long collectionStartNanos;
    @GuardedBy("this")
    private boolean collectionStarted;
    @GuardedBy("this")
    private boolean collectionTimeRecorded;
    @GuardedBy("this")
    private long lastPartitionArrivalNanos;  // 0 = no arrivals yet // DIAGNOSTIC(DPP-PARTITIONED-WAIT)
    @GuardedBy("this")
    private int extensionsUsed;
    @GuardedBy("this")
    private int lastTickPartitionCount;

    /**
     * Back-compat for tests written before {@code maxWaitExtensions} was added.
     * Defaults extensions to 0 (single-cycle wait, matching the pre-adaptive behavior).
     * Production callers (see {@code SplitSourceFactory}) must use the full constructor.
     */
    public JoinDynamicFilter(
            String filterId,
            String columnName,
            Duration waitTimeout,
            long maxSizeInBytes,
            DynamicFilterStats stats,
            RuntimeStats runtimeStats,
            boolean extendedMetrics)
    {
        this(filterId, columnName, waitTimeout, 0, maxSizeInBytes, stats, runtimeStats, extendedMetrics);
    }

    public JoinDynamicFilter(
            String filterId,
            String columnName,
            Duration waitTimeout,
            int maxWaitExtensions,
            long maxSizeInBytes,
            DynamicFilterStats stats,
            RuntimeStats runtimeStats,
            boolean extendedMetrics)
    {
        this.filterId = requireNonNull(filterId, "filterId is null");
        this.columnName = requireNonNull(columnName, "columnName is null");
        this.waitTimeout = requireNonNull(waitTimeout, "waitTimeout is null");
        verify(maxWaitExtensions >= 0, "maxWaitExtensions must be non-negative");
        this.maxWaitExtensions = maxWaitExtensions;
        this.maxSizeInBytes = maxSizeInBytes;
        this.expectedPartitions = Integer.MAX_VALUE;
        this.stats = requireNonNull(stats, "stats is null");
        this.runtimeStats = requireNonNull(runtimeStats, "runtimeStats is null");
        this.extendedMetrics = extendedMetrics;

        this.constraintByFilterIdFuture = new CompletableFuture<>();
    }

    public RuntimeStats getRuntimeStats()
    {
        return runtimeStats;
    }

    public synchronized void setExpectedPartitions(int expectedPartitions)
    {
        verify(expectedPartitions > 0, "expectedPartitions must be positive");
        this.expectedPartitions = expectedPartitions;
        runtimeStats.addMetricValue(DYNAMIC_FILTER_EXPECTED_PARTITIONS, NONE, expectedPartitions);
        if (!filterId.isEmpty()) {
            runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_EXPECTED_PARTITIONS, filterId), NONE, expectedPartitions);
        }
        tryCompleteResolution();
    }

    /**
     * Must be called when wired to a split source, not at registration time,
     * since the filter may be pre-registered well before the split source exists.
     *
     * <p>Schedules a progress-aware deadline. Every {@code waitTimeout} cycle the tick
     * checks whether new partition contributions have arrived since the prior tick;
     * if so and the extension budget is not exhausted, the deadline is extended by
     * one more cycle. Otherwise the future is completed with {@link TupleDomain#all()}
     * (no filter pushed). Total wall time is bounded by
     * {@code (1 + maxWaitExtensions) * waitTimeout}.
     */
    public void startTimeout()
    {
        if (timeoutStarted.compareAndSet(false, true)) {
            long timeoutMs = waitTimeout.toMillis();
            if (timeoutMs > 0) {
                // Baseline the progress snapshot at startTimeout, so any contribution
                // received BEFORE startTimeout does not get credited as new progress
                // on the first tick.
                synchronized (this) {
                    lastTickPartitionCount = partitionsByFilterId.size();
                }
                scheduleTick(timeoutMs);
            }
        }
    }

    private void scheduleTick(long timeoutMs)
    {
        TIMEOUT_SCHEDULER.schedule(this::onTick, timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void onTick()
    {
        boolean reschedule;
        synchronized (this) {
            if (constraintByFilterIdFuture.isDone()) {
                return;
            }
            int currentReceived = partitionsByFilterId.size();
            boolean progress = currentReceived > lastTickPartitionCount;
            lastTickPartitionCount = currentReceived;
            if (progress && extensionsUsed < maxWaitExtensions) {
                extensionsUsed++;
                if (extendedMetrics) {
                    runtimeStats.addMetricValue(DYNAMIC_FILTER_EXTENSION_COUNT, NONE, 1);
                    if (!filterId.isEmpty()) {
                        runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_EXTENSION_COUNT, filterId), NONE, 1);
                    }
                }
                reschedule = true;
            }
            else {
                // Finalize inside the monitor so a concurrent addPartitionByFilterId either
                // resolves the filter first (and this tick observes isDone) or sees the
                // future already completed and bails — partial state is never exposed.
                constraintByFilterIdFuture.complete(TupleDomain.all());
                onTimeout();
                reschedule = false;
            }
        }
        if (reschedule) {
            scheduleTick(waitTimeout.toMillis());
        }
    }

    public Duration getWaitTimeout()
    {
        return waitTimeout;
    }

    public String getFilterId()
    {
        return filterId;
    }

    public String getColumnName()
    {
        return columnName;
    }

    public synchronized void setProbeColumnDomain(Domain domain)
    {
        this.probeColumnDomain = requireNonNull(domain, "domain is null");
    }

    public boolean isComplete()
    {
        return fullyResolved;
    }

    public synchronized void addPartitionByFilterId(TupleDomain<String> tupleDomain)
    {
        requireNonNull(tupleDomain, "tupleDomain is null");
        if (!collectionStarted) {
            collectionStartNanos = System.nanoTime();
            collectionStarted = true;
        }
        lastPartitionArrivalNanos = System.nanoTime();   // DIAGNOSTIC(DPP-PARTITIONED-WAIT)

        partitionsByFilterId.add(tupleDomain);

        runtimeStats.addMetricValue(DYNAMIC_FILTER_PARTITIONS_RECEIVED, NONE, 1);
        if (!filterId.isEmpty()) {
            runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_PARTITIONS_RECEIVED, filterId), NONE, 1);
        }

        tryCompleteResolution();
    }

    @GuardedBy("this")
    private void tryCompleteResolution()
    {
        if (constraintByFilterIdFuture.isDone() || partitionsByFilterId.size() < expectedPartitions) {
            return;
        }

        TupleDomain<String> union = TupleDomain.columnWiseUnion(partitionsByFilterId);
        mergedConstraint = collapseIfOversized(union);
        maybeShortCircuit();
        fullyResolved = true;
        constraintByFilterIdFuture.complete(mergedConstraint);
        recordCollectionCompleted();
    }

    private TupleDomain<String> collapseIfOversized(TupleDomain<String> tupleDomain)
    {
        if (estimateRetainedSizeInBytes(tupleDomain) > maxSizeInBytes) {
            runtimeStats.addMetricValue(DYNAMIC_FILTER_COORDINATOR_FALLBACK_TO_RANGE, NONE, 1);
            if (!filterId.isEmpty()) {
                runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_COORDINATOR_FALLBACK_TO_RANGE, filterId), NONE, 1);
            }
            return collapseToRange(tupleDomain);
        }
        return tupleDomain;
    }

    static long estimateRetainedSizeInBytes(TupleDomain<String> tupleDomain)
    {
        if (tupleDomain.isNone() || tupleDomain.isAll() || !tupleDomain.getDomains().isPresent()) {
            return 0;
        }
        long totalSize = 0;
        for (Domain domain : tupleDomain.getDomains().get().values()) {
            for (Range range : domain.getValues().getRanges().getOrderedRanges()) {
                totalSize += range.getLow().getValueBlock()
                        .map(Block::getRetainedSizeInBytes)
                        .orElse(0L);
                totalSize += range.getHigh().getValueBlock()
                        .map(Block::getRetainedSizeInBytes)
                        .orElse(0L);
            }
        }
        return totalSize;
    }

    static TupleDomain<String> collapseToRange(TupleDomain<String> tupleDomain)
    {
        if (tupleDomain.isNone() || tupleDomain.isAll() || !tupleDomain.getDomains().isPresent()) {
            return tupleDomain;
        }
        ImmutableMap.Builder<String, Domain> collapsed = ImmutableMap.builder();
        for (Map.Entry<String, Domain> entry : tupleDomain.getDomains().get().entrySet()) {
            Domain domain = entry.getValue();
            ValueSet values = domain.getValues();
            if (values instanceof SortedRangeSet) {
                SortedRangeSet sortedRangeSet = (SortedRangeSet) values;
                if (sortedRangeSet.getRangeCount() > 1) {
                    collapsed.put(entry.getKey(), Domain.create(ValueSet.ofRanges(sortedRangeSet.getSpan()), domain.isNullAllowed()));
                    continue;
                }
            }
            collapsed.put(entry.getKey(), domain);
        }
        return TupleDomain.withColumnDomains(collapsed.build());
    }

    private void maybeShortCircuit()
    {
        if (probeColumnDomain == null || mergedConstraint.isAll() || mergedConstraint.isNone()) {
            return;
        }
        if (!mergedConstraint.getDomains().isPresent()) {
            return;
        }
        Map<String, Domain> domains = mergedConstraint.getDomains().get();
        if (domains.size() != 1) {
            return;
        }
        Domain filterDomain = domains.values().iterator().next();
        if (filterDomain.contains(probeColumnDomain)) {
            mergedConstraint = TupleDomain.all();
            runtimeStats.addMetricValue(DYNAMIC_FILTER_SHORT_CIRCUITED, NONE, 1);
            if (!filterId.isEmpty()) {
                runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_SHORT_CIRCUITED, filterId), NONE, 1);
            }
        }
    }

    private void recordCollectionCompleted()
    {
        stats.getFilterCollectionCompleted().update(1);
        recordCollectionTime();
        recordArrivalSpread();   // DIAGNOSTIC(DPP-PARTITIONED-WAIT)
        if (extendedMetrics && !filterId.isEmpty()) {
            runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_DOMAIN_RANGE_COUNT, filterId), NONE, computeRangeCount(mergedConstraint));
        }
    }

    private synchronized void onTimeout()
    {
        if (!filterId.isEmpty()) {
            runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_TIMED_OUT, filterId), NONE, 1);
        }
        // BEGIN DIAGNOSTIC(DPP-PARTITIONED-WAIT)
        recordTimeoutSnapshot();
        // END DIAGNOSTIC(DPP-PARTITIONED-WAIT)
        stats.getFilterCollectionTimedOut().update(1);
        recordCollectionTime();
        recordArrivalSpread();   // DIAGNOSTIC(DPP-PARTITIONED-WAIT)
    }

    // BEGIN DIAGNOSTIC(DPP-PARTITIONED-WAIT)
    private synchronized void recordArrivalSpread()
    {
        if (!extendedMetrics) {
            return;
        }
        if (collectionStarted && lastPartitionArrivalNanos > 0) {
            long spreadNanos = lastPartitionArrivalNanos - collectionStartNanos;
            runtimeStats.addMetricValue(DYNAMIC_FILTER_ARRIVAL_SPREAD_NANOS, NANO, spreadNanos);
            if (!filterId.isEmpty()) {
                runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_ARRIVAL_SPREAD_NANOS, filterId), NANO, spreadNanos);
            }
        }
    }

    private synchronized void recordTimeoutSnapshot()
    {
        if (!extendedMetrics) {
            return;
        }
        int received = partitionsByFilterId.size();
        int expected = expectedPartitions;
        int deficit = Math.max(0, expected - received);
        long sinceLastArrival = lastPartitionArrivalNanos > 0 ? System.nanoTime() - lastPartitionArrivalNanos : -1;

        if (!filterId.isEmpty()) {
            runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_PARTITIONS_AT_TIMEOUT, filterId), NONE, received);
            runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_DEFICIT_AT_TIMEOUT, filterId), NONE, deficit);
            if (sinceLastArrival >= 0) {
                runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_TIME_SINCE_LAST_ARRIVAL_AT_TIMEOUT_NANOS, filterId), NANO, sinceLastArrival);
            }
        }
        runtimeStats.addMetricValue(DYNAMIC_FILTER_PARTITIONS_AT_TIMEOUT, NONE, received);
        runtimeStats.addMetricValue(DYNAMIC_FILTER_DEFICIT_AT_TIMEOUT, NONE, deficit);
        if (sinceLastArrival >= 0) {
            runtimeStats.addMetricValue(DYNAMIC_FILTER_TIME_SINCE_LAST_ARRIVAL_AT_TIMEOUT_NANOS, NANO, sinceLastArrival);
        }
    }
    // END DIAGNOSTIC(DPP-PARTITIONED-WAIT)

    private void recordCollectionTime()
    {
        if (collectionStarted && !collectionTimeRecorded) {
            collectionTimeRecorded = true;
            long elapsedNanos = System.nanoTime() - collectionStartNanos;
            runtimeStats.addMetricValue(DYNAMIC_FILTER_COLLECTION_TIME_NANOS, NANO, elapsedNanos);
            if (!filterId.isEmpty()) {
                runtimeStats.addMetricValue(format("%s[%s]", DYNAMIC_FILTER_COLLECTION_TIME_NANOS, filterId), NANO, elapsedNanos);
            }
        }
    }

    static long computeRangeCount(TupleDomain<String> tupleDomain)
    {
        if (tupleDomain.isNone() || !tupleDomain.getDomains().isPresent()) {
            return 0;
        }
        return tupleDomain.getDomains().get().values().stream()
                .mapToLong(domain -> domain.getValues().getRanges().getRangeCount())
                .sum();
    }

    public CompletableFuture<?> isBlocked()
    {
        if (constraintByFilterIdFuture.isDone()) {
            return NOT_BLOCKED;
        }
        return constraintByFilterIdFuture.thenApply(v -> null);
    }

    /**
     * Returns all() until ALL expected partitions arrive to avoid pruning splits
     * for not-yet-reported workers.
     */
    public synchronized TupleDomain<String> getCurrentConstraintByColumnName()
    {
        if (!fullyResolved || mergedConstraint == null) {
            return TupleDomain.all();
        }
        return translateToColumnName(mergedConstraint);
    }

    private TupleDomain<String> translateToColumnName(TupleDomain<String> filterIdDomain)
    {
        if (columnName.isEmpty() || filterIdDomain.isAll() || filterIdDomain.isNone() ||
                !filterIdDomain.getDomains().isPresent()) {
            return filterIdDomain;
        }

        Map<String, Domain> domains = filterIdDomain.getDomains().get();
        verify(domains.size() == 1, "Expected single-column filter but got %s entries", domains.size());
        Domain domain = domains.values().iterator().next();
        if (domain.isAll()) {
            return TupleDomain.all();
        }
        return TupleDomain.withColumnDomains(ImmutableMap.of(columnName, domain));
    }

    /**
     * Registers a callback that fires when this filter is fully resolved
     * (all expected partitions received, NOT on timeout).
     * The callback receives (filterId, constraintByColumnName).
     */
    public void onFullyResolved(BiConsumer<String, TupleDomain<String>> callback)
    {
        constraintByFilterIdFuture.whenComplete((domain, throwable) -> {
            if (fullyResolved && throwable == null) {
                callback.accept(filterId, getCurrentConstraintByColumnName());
            }
        });
    }

    public synchronized boolean hasData()
    {
        return !partitionsByFilterId.isEmpty();
    }

    public static DynamicFilter createDisabled()
    {
        return DynamicFilter.EMPTY;
    }

    @Override
    public synchronized String toString()
    {
        return toStringHelper(this)
                .add("filterId", filterId)
                .add("columnName", columnName)
                .add("waitTimeout", waitTimeout)
                .add("expectedPartitions", expectedPartitions)
                .add("receivedPartitions", partitionsByFilterId.size())
                .add("complete", fullyResolved)
                .toString();
    }
}
