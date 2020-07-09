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
package com.facebook.presto.execution;

import com.facebook.airlift.stats.Distribution.DistributionSnapshot;
import com.facebook.presto.operator.BlockedReason;
import com.facebook.presto.operator.OperatorStats;
import com.facebook.presto.operator.PipelineStats;
import com.facebook.presto.operator.TaskStats;
import com.facebook.presto.spi.eventlistener.StageGcStatistics;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.airlift.units.DataSize;
import org.joda.time.DateTime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.execution.StageExecutionState.FINISHED;
import static io.airlift.units.DataSize.succinctBytes;
import static io.airlift.units.Duration.succinctDuration;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class StageExecutionInfo
{
    private final StageExecutionState state;
    private final StageExecutionStats stats;
    private final List<TaskInfo> tasks;
    private final Optional<ExecutionFailureInfo> failureCause;

    public static StageExecutionInfo create(
            StageExecutionId stageExecutionId,
            StageExecutionState state,
            Optional<ExecutionFailureInfo> failureInfo,
            List<TaskInfo> taskInfos,
            DateTime schedulingComplete,
            DistributionSnapshot getSplitDistribution,
            DataSize peakUserMemoryReservation,
            int finishedLifespans,
            int totalLifespans)
    {
        int totalTasks = taskInfos.size();
        int runningTasks = 0;
        int completedTasks = 0;

        int totalDrivers = 0;
        int queuedDrivers = 0;
        int runningDrivers = 0;
        int blockedDrivers = 0;
        int completedDrivers = 0;

        long cumulativeUserMemory = 0;
        long userMemoryReservation = 0;
        long totalMemoryReservation = 0;

        long totalScheduledTime = 0;
        long totalCpuTime = 0;
        long retriedCpuTime = 0;
        long totalBlockedTime = 0;

        long totalAllocation = 0;

        long rawInputDataSize = 0;
        long rawInputPositions = 0;

        long processedInputDataSize = 0;
        long processedInputPositions = 0;

        long bufferedDataSize = 0;
        long outputDataSize = 0;
        long outputPositions = 0;

        long physicalWrittenDataSize = 0;

        int fullGcCount = 0;
        int fullGcTaskCount = 0;
        int minFullGcSec = 0;
        int maxFullGcSec = 0;
        int totalFullGcSec = 0;

        boolean fullyBlocked = true;
        Set<BlockedReason> blockedReasons = new HashSet<>();

        Map<String, OperatorStats> operatorToStats = new HashMap<>();
        for (TaskInfo taskInfo : taskInfos) {
            TaskState taskState = taskInfo.getTaskStatus().getState();
            if (taskState.isDone()) {
                completedTasks++;
            }
            else {
                runningTasks++;
            }

            TaskStats taskStats = taskInfo.getStats();

            totalDrivers += taskStats.getTotalDrivers();
            queuedDrivers += taskStats.getQueuedDrivers();
            runningDrivers += taskStats.getRunningDrivers();
            blockedDrivers += taskStats.getBlockedDrivers();
            completedDrivers += taskStats.getCompletedDrivers();

            cumulativeUserMemory += taskStats.getCumulativeUserMemory();

            long taskUserMemory = taskStats.getUserMemoryReservation().toBytes();
            long taskSystemMemory = taskStats.getSystemMemoryReservation().toBytes();
            userMemoryReservation += taskUserMemory;
            totalMemoryReservation += taskUserMemory + taskSystemMemory;

            totalScheduledTime += taskStats.getTotalScheduledTime().roundTo(NANOSECONDS);
            totalCpuTime += taskStats.getTotalCpuTime().roundTo(NANOSECONDS);
            if (state == FINISHED && taskInfo.getTaskStatus().getState() == TaskState.FAILED) {
                retriedCpuTime += taskStats.getTotalCpuTime().roundTo(NANOSECONDS);
            }
            totalBlockedTime += taskStats.getTotalBlockedTime().roundTo(NANOSECONDS);
            if (!taskState.isDone()) {
                fullyBlocked &= taskStats.isFullyBlocked();
                blockedReasons.addAll(taskStats.getBlockedReasons());
            }

            totalAllocation += taskStats.getTotalAllocation().toBytes();

            rawInputDataSize += taskStats.getRawInputDataSize().toBytes();
            rawInputPositions += taskStats.getRawInputPositions();

            processedInputDataSize += taskStats.getProcessedInputDataSize().toBytes();
            processedInputPositions += taskStats.getProcessedInputPositions();

            bufferedDataSize += taskInfo.getOutputBuffers().getTotalBufferedBytes();
            outputDataSize += taskStats.getOutputDataSize().toBytes();
            outputPositions += taskStats.getOutputPositions();

            physicalWrittenDataSize += taskStats.getPhysicalWrittenDataSize().toBytes();

            fullGcCount += taskStats.getFullGcCount();
            fullGcTaskCount += taskStats.getFullGcCount() > 0 ? 1 : 0;

            int gcSec = toIntExact(taskStats.getFullGcTime().roundTo(SECONDS));
            totalFullGcSec += gcSec;
            minFullGcSec = min(minFullGcSec, gcSec);
            maxFullGcSec = max(maxFullGcSec, gcSec);

            for (PipelineStats pipeline : taskStats.getPipelines()) {
                for (OperatorStats operatorStats : pipeline.getOperatorSummaries()) {
                    String id = pipeline.getPipelineId() + "." + operatorStats.getOperatorId();
                    operatorToStats.compute(id, (k, v) -> v == null ? operatorStats : v.add(operatorStats));
                }
            }
        }

        StageExecutionStats stageExecutionStats = new StageExecutionStats(
                schedulingComplete,
                getSplitDistribution,

                totalTasks,
                runningTasks,
                completedTasks,

                totalLifespans,
                finishedLifespans,

                totalDrivers,
                queuedDrivers,
                runningDrivers,
                blockedDrivers,
                completedDrivers,

                cumulativeUserMemory,
                succinctBytes(userMemoryReservation),
                succinctBytes(totalMemoryReservation),
                peakUserMemoryReservation,
                succinctDuration(totalScheduledTime, NANOSECONDS),
                succinctDuration(totalCpuTime, NANOSECONDS),
                succinctDuration(retriedCpuTime, NANOSECONDS),
                succinctDuration(totalBlockedTime, NANOSECONDS),
                fullyBlocked && runningTasks > 0,
                blockedReasons,

                succinctBytes(totalAllocation),

                succinctBytes(rawInputDataSize),
                rawInputPositions,
                succinctBytes(processedInputDataSize),
                processedInputPositions,
                succinctBytes(bufferedDataSize),
                succinctBytes(outputDataSize),
                outputPositions,
                succinctBytes(physicalWrittenDataSize),

                new StageGcStatistics(
                        stageExecutionId.getStageId().getId(),
                        stageExecutionId.getId(),
                        totalTasks,
                        fullGcTaskCount,
                        minFullGcSec,
                        maxFullGcSec,
                        totalFullGcSec,
                        (int) (1.0 * totalFullGcSec / fullGcCount)),

                ImmutableList.copyOf(operatorToStats.values()));

        return new StageExecutionInfo(
                state,
                stageExecutionStats,
                taskInfos,
                failureInfo);
    }

    @JsonCreator
    public StageExecutionInfo(
            @JsonProperty("state") StageExecutionState state,
            @JsonProperty("stats") StageExecutionStats stats,
            @JsonProperty("tasks") List<TaskInfo> tasks,
            @JsonProperty("failureCause") Optional<ExecutionFailureInfo> failureCause)
    {
        this.state = requireNonNull(state, "state is null");
        this.stats = requireNonNull(stats, "stats is null");
        this.tasks = ImmutableList.copyOf(requireNonNull(tasks, "tasks is null"));
        this.failureCause = requireNonNull(failureCause, "failureCause is null");
    }

    @JsonProperty
    public StageExecutionState getState()
    {
        return state;
    }

    @JsonProperty
    public StageExecutionStats getStats()
    {
        return stats;
    }

    @JsonProperty
    public List<TaskInfo> getTasks()
    {
        return tasks;
    }

    @JsonProperty
    public Optional<ExecutionFailureInfo> getFailureCause()
    {
        return failureCause;
    }

    public boolean isFinal()
    {
        return state.isDone() && tasks.stream().allMatch(taskInfo -> taskInfo.getTaskStatus().getState().isDone());
    }

    public static StageExecutionInfo unscheduledExecutionInfo(int stageId, boolean isQueryDone)
    {
        return new StageExecutionInfo(
                isQueryDone ? StageExecutionState.ABORTED : StageExecutionState.PLANNED,
                StageExecutionStats.zero(stageId),
                ImmutableList.of(),
                Optional.empty());
    }
}
