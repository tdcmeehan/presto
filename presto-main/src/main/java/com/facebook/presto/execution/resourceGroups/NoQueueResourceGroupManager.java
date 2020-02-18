package com.facebook.presto.execution.resourceGroups;

import com.facebook.presto.execution.ManagedQueryExecution;
import com.facebook.presto.server.ResourceGroupInfo;
import com.facebook.presto.spi.resourceGroups.ResourceGroupConfigurationManagerFactory;
import com.facebook.presto.spi.resourceGroups.ResourceGroupId;
import com.facebook.presto.spi.resourceGroups.SelectionContext;
import com.facebook.presto.spi.resourceGroups.SelectionCriteria;
import com.facebook.presto.sql.tree.Statement;
import com.google.common.collect.ImmutableList;
import io.airlift.units.DataSize;

import java.util.List;
import java.util.concurrent.Executor;

import static com.facebook.presto.spi.resourceGroups.ResourceGroupState.CAN_RUN;
import static com.facebook.presto.spi.resourceGroups.SchedulingPolicy.QUERY_PRIORITY;

public class NoQueueResourceGroupManager
        implements ResourceGroupManager<Object>
{
    @Override
    public void submit(Statement statement, ManagedQueryExecution queryExecution, SelectionContext<Object> selectionContext, Executor executor)
    {
        executor.execute(queryExecution::startWaitingForResources);
    }

    @Override
    public SelectionContext<Object> selectGroup(SelectionCriteria criteria)
    {
        return new SelectionContext<>(new ResourceGroupId("global"), "");
    }

    @Override
    public ResourceGroupInfo getResourceGroupInfo(ResourceGroupId id)
    {
        return new ResourceGroupInfo(
                id,
                CAN_RUN,
                QUERY_PRIORITY,
                0,
                DataSize.succinctBytes(0),
                0,
                0,
                0,
                DataSize.succinctBytes(0),
                0,
                0,
                0,
                ImmutableList.of(),
                ImmutableList.of());
    }

    @Override
    public List<ResourceGroupInfo> getPathToRoot(ResourceGroupId id)
    {
        return ImmutableList.of();
    }

    @Override
    public void addConfigurationManagerFactory(ResourceGroupConfigurationManagerFactory factory)
    {
        // No op
    }

    @Override
    public void loadConfigurationManager()
    {
        // No op
    }
}
