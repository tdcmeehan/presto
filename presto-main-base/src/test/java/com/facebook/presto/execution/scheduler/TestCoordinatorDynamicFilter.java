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
import com.facebook.presto.common.predicate.Domain;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.spi.ColumnHandle;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.testng.annotations.Test;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.IntegerType.INTEGER;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestCoordinatorDynamicFilter
{
    private static final Duration DEFAULT_TIMEOUT = new Duration(2, TimeUnit.SECONDS);

    @Test
    public void testSimpleSinglePartition()
            throws ExecutionException, InterruptedException
    {
        TestColumnHandle columnA = new TestColumnHandle("column_a");
        Set<ColumnHandle> columns = ImmutableSet.of(columnA);

        CoordinatorDynamicFilter filter = new CoordinatorDynamicFilter(
                columns,
                DEFAULT_TIMEOUT,
                1);

        assertFalse(filter.isComplete());

        // Add single partition with a single value domain
        filter.addPartition(TupleDomain.withColumnDomains(
                com.google.common.collect.ImmutableMap.of(columnA, Domain.singleValue(INTEGER, 42L))));

        // Filter should complete immediately with single partition
        assertTrue(filter.isComplete());
        assertEquals(
                filter.getConstraint().get(),
                TupleDomain.withColumnDomains(
                        com.google.common.collect.ImmutableMap.of(columnA, Domain.singleValue(INTEGER, 42L))));
    }

    @Test
    public void testMultiplePartitions()
            throws ExecutionException, InterruptedException
    {
        TestColumnHandle columnA = new TestColumnHandle("column_a");
        Set<ColumnHandle> columns = ImmutableSet.of(columnA);

        CoordinatorDynamicFilter filter = new CoordinatorDynamicFilter(
                columns,
                DEFAULT_TIMEOUT,
                3);

        assertFalse(filter.isComplete());

        // Add first partition
        filter.addPartition(TupleDomain.withColumnDomains(
                com.google.common.collect.ImmutableMap.of(columnA, Domain.singleValue(INTEGER, 10L))));
        assertFalse(filter.isComplete());

        // Add second partition
        filter.addPartition(TupleDomain.withColumnDomains(
                com.google.common.collect.ImmutableMap.of(columnA, Domain.singleValue(INTEGER, 20L))));
        assertFalse(filter.isComplete());

        // Add third partition - should complete
        filter.addPartition(TupleDomain.withColumnDomains(
                com.google.common.collect.ImmutableMap.of(columnA, Domain.singleValue(INTEGER, 30L))));
        assertTrue(filter.isComplete());

        // Verify merged result contains all three values
        assertEquals(
                filter.getConstraint().get(),
                TupleDomain.withColumnDomains(
                        com.google.common.collect.ImmutableMap.of(columnA, Domain.multipleValues(INTEGER, ImmutableList.of(10L, 20L, 30L)))));
    }

    @Test
    public void testColumnWiseUnion()
            throws ExecutionException, InterruptedException
    {
        TestColumnHandle columnA = new TestColumnHandle("column_a");
        TestColumnHandle columnB = new TestColumnHandle("column_b");
        Set<ColumnHandle> columns = ImmutableSet.of(columnA, columnB);

        CoordinatorDynamicFilter filter = new CoordinatorDynamicFilter(
                columns,
                DEFAULT_TIMEOUT,
                2);

        // First partition: a=10, b=100
        filter.addPartition(TupleDomain.withColumnDomains(
                com.google.common.collect.ImmutableMap.of(
                        columnA, Domain.singleValue(INTEGER, 10L),
                        columnB, Domain.singleValue(BIGINT, 100L))));

        // Second partition: a=20, b=200
        filter.addPartition(TupleDomain.withColumnDomains(
                com.google.common.collect.ImmutableMap.of(
                        columnA, Domain.singleValue(INTEGER, 20L),
                        columnB, Domain.singleValue(BIGINT, 200L))));

        assertTrue(filter.isComplete());

        // Verify columnWiseUnion: a IN (10, 20), b IN (100, 200)
        TupleDomain<ColumnHandle> result = filter.getConstraint().get();
        assertEquals(
                result,
                TupleDomain.withColumnDomains(
                        com.google.common.collect.ImmutableMap.of(
                                columnA, Domain.multipleValues(INTEGER, ImmutableList.of(10L, 20L)),
                                columnB, Domain.multipleValues(BIGINT, ImmutableList.of(100L, 200L)))));
    }

    @Test
    public void testEmptyPartition()
            throws ExecutionException, InterruptedException
    {
        TestColumnHandle columnA = new TestColumnHandle("column_a");
        Set<ColumnHandle> columns = ImmutableSet.of(columnA);

        CoordinatorDynamicFilter filter = new CoordinatorDynamicFilter(
                columns,
                DEFAULT_TIMEOUT,
                2);

        // First partition: a=10
        filter.addPartition(TupleDomain.withColumnDomains(
                com.google.common.collect.ImmutableMap.of(columnA, Domain.singleValue(INTEGER, 10L))));

        // Second partition: TupleDomain.all() (no constraint)
        filter.addPartition(TupleDomain.all());

        assertTrue(filter.isComplete());

        // With TupleDomain.all(), the union becomes all() (no filtering)
        assertEquals(filter.getConstraint().get(), TupleDomain.all());
    }

    @Test
    public void testNonePartition()
            throws ExecutionException, InterruptedException
    {
        TestColumnHandle columnA = new TestColumnHandle("column_a");
        Set<ColumnHandle> columns = ImmutableSet.of(columnA);

        CoordinatorDynamicFilter filter = new CoordinatorDynamicFilter(
                columns,
                DEFAULT_TIMEOUT,
                2);

        // First partition: a=10
        filter.addPartition(TupleDomain.withColumnDomains(
                com.google.common.collect.ImmutableMap.of(columnA, Domain.singleValue(INTEGER, 10L))));

        // Second partition: TupleDomain.none() (contradictory)
        filter.addPartition(TupleDomain.none());

        assertTrue(filter.isComplete());

        // With TupleDomain.none(), the union should still contain 10
        // (union of {10} and {} is {10} in value set terms)
        TupleDomain<ColumnHandle> result = filter.getConstraint().get();
        assertEquals(
                result,
                TupleDomain.withColumnDomains(
                        com.google.common.collect.ImmutableMap.of(columnA, Domain.singleValue(INTEGER, 10L))));
    }

    @Test
    public void testIsComplete()
    {
        TestColumnHandle columnA = new TestColumnHandle("column_a");
        Set<ColumnHandle> columns = ImmutableSet.of(columnA);

        CoordinatorDynamicFilter filter = new CoordinatorDynamicFilter(
                columns,
                DEFAULT_TIMEOUT,
                2);

        // Not complete initially
        assertFalse(filter.isComplete());

        // Still not complete after first partition
        filter.addPartition(TupleDomain.withColumnDomains(
                com.google.common.collect.ImmutableMap.of(columnA, Domain.singleValue(INTEGER, 10L))));
        assertFalse(filter.isComplete());

        // Complete after second partition
        filter.addPartition(TupleDomain.withColumnDomains(
                com.google.common.collect.ImmutableMap.of(columnA, Domain.singleValue(INTEGER, 20L))));
        assertTrue(filter.isComplete());
    }

    @Test
    public void testGetColumnsCovered()
    {
        TestColumnHandle columnA = new TestColumnHandle("column_a");
        TestColumnHandle columnB = new TestColumnHandle("column_b");
        Set<ColumnHandle> columns = ImmutableSet.of(columnA, columnB);

        CoordinatorDynamicFilter filter = new CoordinatorDynamicFilter(
                columns,
                DEFAULT_TIMEOUT,
                1);

        assertEquals(filter.getColumnsCovered(), columns);
    }

    @Test
    public void testGetWaitTimeout()
    {
        TestColumnHandle columnA = new TestColumnHandle("column_a");
        Set<ColumnHandle> columns = ImmutableSet.of(columnA);

        Duration timeout = new Duration(5, TimeUnit.SECONDS);
        CoordinatorDynamicFilter filter = new CoordinatorDynamicFilter(
                columns,
                timeout,
                1);

        assertEquals(filter.getWaitTimeout(), timeout);
    }

    @Test
    public void testCreateDisabled()
    {
        // Verify disabled filter returns EMPTY constant
        assertEquals(CoordinatorDynamicFilter.createDisabled(), com.facebook.presto.spi.connector.DynamicFilter.EMPTY);
    }

    /**
     * Simple test implementation of ColumnHandle for unit testing.
     */
    private static class TestColumnHandle
            implements ColumnHandle
    {
        private final String name;

        public TestColumnHandle(String name)
        {
            this.name = name;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TestColumnHandle that = (TestColumnHandle) o;
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(name);
        }

        @Override
        public String toString()
        {
            return "TestColumnHandle{name='" + name + "'}";
        }
    }
}
