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
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.connector.DynamicFilter;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Verify.verify;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

/**
 * Coordinator-side dynamic filter that accumulates filter constraints from multiple workers.
 *
 * <p>This filter implements the {@link DynamicFilter} SPI interface and collects
 * TupleDomains from distributed workers, merging them using columnWiseUnion.
 * The merged result is provided to connectors during split scheduling for
 * partition and file pruning.
 *
 * <p>The filter is keyed by {@link ColumnHandle} (connector-level) rather than
 * VariableReferenceExpression (planner-internal), making it suitable for
 * connector consumption.
 */
@ThreadSafe
public class CoordinatorDynamicFilter
        implements DynamicFilter
{
    private final Set<ColumnHandle> columnsCovered;
    private final Duration waitTimeout;
    private final int expectedPartitions;

    private final CompletableFuture<TupleDomain<ColumnHandle>> constraintFuture;

    @GuardedBy("this")
    private final List<TupleDomain<ColumnHandle>> partitions;

    public CoordinatorDynamicFilter(
            Set<ColumnHandle> columnsCovered,
            Duration waitTimeout,
            int expectedPartitions)
    {
        this.columnsCovered = unmodifiableSet(requireNonNull(columnsCovered, "columnsCovered is null"));
        this.waitTimeout = requireNonNull(waitTimeout, "waitTimeout is null");
        verify(expectedPartitions > 0, "expectedPartitions must be positive");
        this.expectedPartitions = expectedPartitions;

        this.constraintFuture = new CompletableFuture<>();
        this.partitions = new ArrayList<>(expectedPartitions);
    }

    @Override
    public Set<ColumnHandle> getColumnsCovered()
    {
        return columnsCovered;
    }

    @Override
    public CompletableFuture<TupleDomain<ColumnHandle>> getConstraint()
    {
        return constraintFuture;
    }

    @Override
    public Duration getWaitTimeout()
    {
        return waitTimeout;
    }

    @Override
    public boolean isComplete()
    {
        return constraintFuture.isDone();
    }

    /**
     * Add a partition's filter constraint from a worker.
     *
     * <p>Called concurrently by each worker as they complete their build-side
     * hash table construction. When all expected partitions have been received,
     * the constraints are merged using columnWiseUnion and the result future
     * is completed.
     *
     * @param tupleDomain the filter constraint from one worker partition
     */
    public synchronized void addPartition(TupleDomain<ColumnHandle> tupleDomain)
    {
        requireNonNull(tupleDomain, "tupleDomain is null");
        verify(!constraintFuture.isDone(), "Filter already completed");
        verify(partitions.size() < expectedPartitions, "Already received all expected partitions");

        partitions.add(tupleDomain);

        if (partitions.size() == expectedPartitions) {
            // All partitions received - merge and complete
            // NOTE: columnWiseUnion may result in a more relaxed constraint when
            // there are multiple columns and multiple rows. See TupleDomain javadoc.
            TupleDomain<ColumnHandle> merged = TupleDomain.columnWiseUnion(partitions);
            constraintFuture.complete(merged);
        }
    }

    /**
     * Returns a disabled dynamic filter.
     *
     * <p>Use this when dynamic partition pruning is disabled via session property
     * or when the query doesn't have applicable dynamic filters.
     *
     * @return the empty dynamic filter constant
     */
    public static DynamicFilter createDisabled()
    {
        return DynamicFilter.EMPTY;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("columnsCovered", columnsCovered)
                .add("waitTimeout", waitTimeout)
                .add("expectedPartitions", expectedPartitions)
                .add("receivedPartitions", partitions.size())
                .add("complete", constraintFuture.isDone())
                .toString();
    }
}
