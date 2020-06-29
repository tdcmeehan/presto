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
package com.facebook.presto.hive;

import com.facebook.airlift.log.Logger;
import com.facebook.airlift.stats.CounterStat;
import com.facebook.presto.hive.metastore.Column;
import com.facebook.presto.hive.metastore.StorageFormat;
import com.facebook.presto.hive.metastore.Table;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorSplit;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.testing.TestingConnectorSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.facebook.airlift.concurrent.Threads.daemonThreadsNamed;
import static com.facebook.presto.hive.BackgroundHiveSplitLoader.BucketSplitInfo.createBucketSplitInfo;
import static com.facebook.presto.hive.CacheQuotaScope.GLOBAL;
import static com.facebook.presto.hive.HiveTestUtils.SESSION;
import static com.facebook.presto.hive.HiveType.HIVE_STRING;
import static com.facebook.presto.hive.metastore.PrestoTableType.MANAGED_TABLE;
import static com.facebook.presto.spi.connector.NotPartitionedPartitionHandle.NOT_PARTITIONED;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static java.util.concurrent.Executors.newCachedThreadPool;

@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 1)
@Warmup(iterations = 1)
@Fork(1)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkHiveSplitSource
{
    private static final Logger LOGGER = Logger.get(BenchmarkHiveSplitSource.class);
    private static final LocatedFileStatus SAMPLE_PATH = locatedFileStatus(new Path("hdfs://VOL1:9000/db_name/table_name/000000_0"));
    private static final ExecutorService EXECUTOR = newCachedThreadPool(daemonThreadsNamed("test-%s"));

    private static final Table SIMPLE_TABLE = table(ImmutableList.of(), Optional.empty());
//    @Param({"500000", "5000000"})
    @Param({"1"})
    public int splits;
//    @Param({"1", "8"})
    @Param({"1"})
    public int loaderThreads;
//    @Param({"1", "64"})
    @Param({"1"})
    public int partitions;

    private HiveSplitSource hiveSplitSource;

    @Setup(Level.Invocation)
    public void setup()
    {
        LOGGER.info("In the setup method");
        List<LocatedFileStatus> testFiles = new ArrayList<>();
        for (int i = 0; i < splits; i++) {
            testFiles.add(SAMPLE_PATH);
        }
        BackgroundHiveSplitLoader backgroundHiveSplitLoader = backgroundHiveSplitLoader(
                testFiles,
                new HadoopDirectoryLister(),
                "test_dbname.test_table");
        this.hiveSplitSource = hiveSplitSource(backgroundHiveSplitLoader);
        backgroundHiveSplitLoader.start(hiveSplitSource);
    }

    @Benchmark
    public void testCachingDirectoryLister(Blackhole bh)
            throws Exception
    {
        long splitsCount = 0;
        while (!hiveSplitSource.isFinished()) {
            List<ConnectorSplit> splits = hiveSplitSource
                    .getNextBatch(NOT_PARTITIONED, 100_000)
                    .get()
                    .getSplits();
            splitsCount += splits.size();
            bh.consume(splits);
        }
        LOGGER.info("Drained %d", splitsCount);
    }

    private BackgroundHiveSplitLoader backgroundHiveSplitLoader(List<LocatedFileStatus> files, DirectoryLister directoryLister, String fileStatusCacheTables)
    {
        HivePartitionMetadata part = new HivePartitionMetadata(
                new HivePartition(new SchemaTableName("testSchema", "table_name")), Optional.empty(), ImmutableMap.of(), Optional.empty());
        List<HivePartitionMetadata> hivePartitionMetadatas = IntStream.range(0, partitions).mapToObj(x -> part).collect(Collectors.toList());

        ConnectorSession connectorSession = new TestingConnectorSession(
                new HiveSessionProperties(
                        new HiveClientConfig()
                                .setMaxSplitSize(new DataSize(1.0, GIGABYTE))
                                .setFileStatusCacheTables(fileStatusCacheTables)
                                .setMaxOutstandingSplitsSize(DataSize.succinctBytes(10L << 30))
                                .setMaxOutstandingSplits(10_000_000),
                        new OrcFileWriterConfig(),
                        new ParquetFileWriterConfig()).getSessionProperties());

        return new BackgroundHiveSplitLoader(
                SIMPLE_TABLE,
                hivePartitionMetadatas,
                Optional.empty(),
                createBucketSplitInfo(Optional.empty(), Optional.empty()),
                connectorSession,
                new TestingHdfsEnvironment(files.subList(0, files.size() / hivePartitionMetadatas.size())),
                new NamenodeStats(),
                directoryLister,
                EXECUTOR,
                loaderThreads,
                false,
                false);
    }

    private static HiveSplitSource hiveSplitSource(BackgroundHiveSplitLoader backgroundHiveSplitLoader)
    {
        return HiveSplitSource.allAtOnce(
                SESSION,
                SIMPLE_TABLE.getDatabaseName(),
                SIMPLE_TABLE.getTableName(),
                GLOBAL,
                Optional.empty(),
                10_000_000,
                10_000_000,
                new DataSize(32, GIGABYTE),
                backgroundHiveSplitLoader,
                EXECUTOR,
                new CounterStat());
    }

    private static Table table(
            List<Column> partitionColumns,
            Optional<HiveBucketProperty> bucketProperty)
    {
        Table.Builder tableBuilder = Table.builder();
        tableBuilder.getStorageBuilder()
                .setStorageFormat(
                        StorageFormat.create(
                                "com.facebook.hive.orc.OrcSerde",
                                "org.apache.hadoop.hive.ql.io.RCFileInputFormat",
                                "org.apache.hadoop.hive.ql.io.RCFileInputFormat"))
                .setLocation("hdfs://VOL1:9000/db_name/table_name")
                .setSkewed(false)
                .setBucketProperty(bucketProperty);

        return tableBuilder
                .setDatabaseName("test_dbname")
                .setOwner("testOwner")
                .setTableName("test_table")
                .setTableType(MANAGED_TABLE)
                .setDataColumns(ImmutableList.of(new Column("col1", HIVE_STRING, Optional.empty())))
                .setParameters(ImmutableMap.of())
                .setPartitionColumns(partitionColumns)
                .build();
    }

    private static LocatedFileStatus locatedFileStatus(Path path)
    {
        return new LocatedFileStatus(
                0L,
                false,
                0,
                0L,
                0L,
                0L,
                null,
                null,
                null,
                null,
                path,
                new BlockLocation[] {new BlockLocation()});
    }

    public static void main(String[] args)
            throws RunnerException
    {
        LOGGER.info("In the main method");
        LOGGER.info("args: " + args + ", " + args.length);
        System.out.println("args: " + args.length);
        Options opt = new OptionsBuilder()
                .include(BenchmarkHiveSplitSource.class.getSimpleName())
                .build();

        new Runner(opt).run();
    }
}
