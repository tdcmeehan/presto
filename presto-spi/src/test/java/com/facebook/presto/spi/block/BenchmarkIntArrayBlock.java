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
package com.facebook.presto.spi.block;

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
import org.openjdk.jmh.profile.LinuxPerfAsmProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

@SuppressWarnings("MethodMayBeStatic")
@State(Scope.Thread)
@OutputTimeUnit(NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
@Measurement(iterations = 10, time = 1000, timeUnit = MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkIntArrayBlock
{
    @State(Scope.Thread)
    public static class TraversalBenchmarkData
    {
        @Param("32779")
        private int size = 32779;

        @Param({"true", "false"})
        private boolean randomize;

        private int[] array;
        private IntArrayBlock intArrayBlock;

        @Setup(Level.Iteration)
        public void setup()
        {
            array = new int[size];

            List<Integer> jumpTo = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                jumpTo.add(i);
            }
            if (randomize) {
                Collections.shuffle(jumpTo);
            }

            for (int i = 0; i < size; i++) {
                array[i] = jumpTo.get(i);
            }

            intArrayBlock = new IntArrayBlock(array.length, Optional.empty(), array);
        }
    }

    @State(Scope.Thread)
    public static class ExpressionBenchmarkData
    {
        @Param("32779")
        private int size = 32780;

        private int[] array1;
        private int[] array2;
        private int[] array3;
        private int[] array4;
        private IntArrayBlock intArrayBlock1;
        private IntArrayBlock intArrayBlock2;
        private IntArrayBlock intArrayBlock3;
        private IntArrayBlock intArrayBlock4;

        @Setup(Level.Iteration)
        public void setup()
        {
            array1 = new int[size];
            array2 = new int[size];
            array3 = new int[size];
            array4 = new int[size];

            Random random = new Random(System.currentTimeMillis());
            for (int i = 0; i < size; i++) {
                array1[i] = random.nextInt();
                array2[i] = random.nextInt();
                array3[i] = random.nextInt();
                array4[i] = random.nextInt();
            }

            intArrayBlock1 = new IntArrayBlock(array1.length, Optional.empty(), array1);
            intArrayBlock2 = new IntArrayBlock(array2.length, Optional.empty(), array2);
            intArrayBlock3 = new IntArrayBlock(array3.length, Optional.empty(), array3);
            intArrayBlock4 = new IntArrayBlock(array4.length, Optional.empty(), array4);
        }
    }

//    @Benchmark
//    public long benchmarkBlockDirectArray(TraversalBenchmarkData data)
//    {
//        long result = 0;
//        int index = 0;
//        for (int i = 0; i < data.intArrayBlock.values.length; i++) {
//            result += index;
//            index = data.intArrayBlock.values[index];
//        }
//        return result;
//    }
//
//    @Benchmark
//    public long benchmarkBlockAccessedByInterface(TraversalBenchmarkData data)
//    {
//        long result = 0;
//        int index = 0;
//        for (int i = 0; i < data.intArrayBlock.getPositionCount(); i++) {
//            result += index;
//            index = data.intArrayBlock.getInt(index, 0);
//        }
//        return result;
//    }
//
//    @Benchmark
//    public long benchmarkBlockAccessedByStaticMethod(TraversalBenchmarkData data)
//    {
//        long result = 0;
//        int index = 0;
//        for (int i = 0; i < data.intArrayBlock.getPositionCount(); i++) {
//            result += index;
//            index = IntArrayBlock.retrieve(data.intArrayBlock, index);
//        }
//        return result;
//    }

    @Benchmark
    public long benchmarkBlockDirectArrayComplexExpression(ExpressionBenchmarkData data)
    {
        long result = 0;
        for (int i = 0; i + 4 < data.intArrayBlock1.values.length; ) {
            result = computeArray(data, i);
            i += 4;
        }
        return result;
    }

    @Benchmark
    public long benchmarkBlockAccessedByInterfaceComplexExpression(ExpressionBenchmarkData data)
    {
        long result = 0;
        for (int i = 0; i + 4 < data.intArrayBlock1.getPositionCount(); ) {
            result = computeBlock(data, i);
            i += 4;
        }
        return result;
    }

    @Benchmark
    public long benchmarkBlockAccessedByInterfaceUnsafeComplexExpression(ExpressionBenchmarkData data)
    {
        long result = 0;
        for (int i = 0; i + 4 < data.intArrayBlock1.getPositionCount(); ) {
            result = computeBlockUnsafe(data, i);
            i += 4;
        }
        return result;
    }

//    @Benchmark
//    public long benchmarkBlockAccessedByStaticMethodComplexExpression(ExpressionBenchmarkData data)
//    {
//        long result = 0;
//        int index = 0;
//        for (int i = 0; i < data.intArrayBlock.getPositionCount(); i++) {
//            result += index;
//            index = IntArrayBlock.retrieve(data.intArrayBlock, index);
//        }
//        return result;
//    }

    private long computeArray(ExpressionBenchmarkData data, int i)
    {
        int a = data.array1[i];
        int b = data.array2[i];
        int c = data.array3[i];
        int d = data.array4[i];
        return a * (1 - b) - c * d;
    }

    private long computeBlock(ExpressionBenchmarkData data, int i)
    {
        int a = data.intArrayBlock1.getInt(i, 0);
        int b = data.intArrayBlock2.getInt(i, 0);
        int c = data.intArrayBlock3.getInt(i, 0);
        int d = data.intArrayBlock4.getInt(i, 0);
        return a * (1 - b) - c * d;
    }

    private long computeBlockUnsafe(ExpressionBenchmarkData data, int i)
    {
        int a = data.intArrayBlock1.getIntUnsafe(i);
        int b = data.intArrayBlock2.getIntUnsafe(i);
        int c = data.intArrayBlock3.getIntUnsafe(i);
        int d = data.intArrayBlock4.getIntUnsafe(i);
        return a * (1 - b) - c * d;
    }

//    private long computeStaticAccessor(ExpressionBenchmarkData data, int i)
//    {
//        int a = IntArrayBlock.retrieve(data.intArrayBlock, i);
//        int b = IntArrayBlock.retrieve(data.intArrayBlock, i + 1);
//        int c = IntArrayBlock.retrieve(data.intArrayBlock, i + 2);
//        int d = IntArrayBlock.retrieve(data.intArrayBlock, i + 3);
//        return a * (1 - b) - c * d;
//    }

    public static void main(String[] args)
            throws Throwable
    {
        Options options = new OptionsBuilder()
                .verbosity(VerboseMode.NORMAL)
                .include(".*" + BenchmarkIntArrayBlock.class.getSimpleName() + ".*")
                .addProfiler(LinuxPerfAsmProfiler.class, "")
                //.jvmArgs("-XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly")
                .build();

        new Runner(options).run();
    }
}
