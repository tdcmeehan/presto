package com.facebook.presto.hive;

import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.spi.ColumnHandle;
import com.fasterxml.jackson.annotation.JsonProperty;

public interface SupportsPartitionColumnPredicate
{
    @JsonProperty
    TupleDomain<ColumnHandle> getPartitionColumnPredicate();
}
