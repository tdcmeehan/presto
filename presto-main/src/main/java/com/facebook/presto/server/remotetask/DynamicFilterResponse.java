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

import com.facebook.presto.common.predicate.TupleDomain;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public class DynamicFilterResponse
{
    private final Map<String, TupleDomain<String>> filters;
    private final long version;
    private final boolean operatorCompleted;
    private final Set<String> completedFilterIds;
    private final Set<String> notGeneratedFilterIds;

    @JsonCreator
    public DynamicFilterResponse(
            @JsonProperty("filters") Map<String, TupleDomain<String>> filters,
            @JsonProperty("version") long version,
            @JsonProperty("operatorCompleted") boolean operatorCompleted,
            @JsonProperty("completedFilterIds") Set<String> completedFilterIds,
            @JsonProperty("notGeneratedFilterIds") Set<String> notGeneratedFilterIds)
    {
        this.filters = ImmutableMap.copyOf(requireNonNull(filters, "filters is null"));
        this.version = version;
        this.operatorCompleted = operatorCompleted;
        this.completedFilterIds = completedFilterIds == null ? ImmutableSet.of() : ImmutableSet.copyOf(completedFilterIds);
        this.notGeneratedFilterIds = notGeneratedFilterIds == null ? ImmutableSet.of() : ImmutableSet.copyOf(notGeneratedFilterIds);
    }

    public DynamicFilterResponse(Map<String, TupleDomain<String>> filters, long version, boolean operatorCompleted, Set<String> completedFilterIds)
    {
        this(filters, version, operatorCompleted, completedFilterIds, ImmutableSet.of());
    }

    public static DynamicFilterResponse incomplete(Map<String, TupleDomain<String>> filters, long version)
    {
        return new DynamicFilterResponse(filters, version, false, ImmutableSet.of(), ImmutableSet.of());
    }

    public static DynamicFilterResponse incomplete(Map<String, TupleDomain<String>> filters, long version, Set<String> completedFilterIds)
    {
        return new DynamicFilterResponse(filters, version, false, completedFilterIds, ImmutableSet.of());
    }

    public static DynamicFilterResponse incomplete(Map<String, TupleDomain<String>> filters, long version, Set<String> completedFilterIds, Set<String> notGeneratedFilterIds)
    {
        return new DynamicFilterResponse(filters, version, false, completedFilterIds, notGeneratedFilterIds);
    }

    public static DynamicFilterResponse completed(Map<String, TupleDomain<String>> filters, long version, Set<String> completedFilterIds)
    {
        return new DynamicFilterResponse(filters, version, true, completedFilterIds, ImmutableSet.of());
    }

    public static DynamicFilterResponse completed(Map<String, TupleDomain<String>> filters, long version, Set<String> completedFilterIds, Set<String> notGeneratedFilterIds)
    {
        return new DynamicFilterResponse(filters, version, true, completedFilterIds, notGeneratedFilterIds);
    }

    @JsonProperty
    public Map<String, TupleDomain<String>> getFilters()
    {
        return filters;
    }

    @JsonProperty
    public long getVersion()
    {
        return version;
    }

    @JsonProperty
    public boolean isOperatorCompleted()
    {
        return operatorCompleted;
    }

    @JsonProperty
    public Set<String> getCompletedFilterIds()
    {
        return completedFilterIds;
    }

    @JsonProperty
    public Set<String> getNotGeneratedFilterIds()
    {
        return notGeneratedFilterIds;
    }

    @Override
    public String toString()
    {
        return "DynamicFilterResponse{" +
                "filters=" + filters +
                ", version=" + version +
                ", operatorCompleted=" + operatorCompleted +
                ", completedFilterIds=" + completedFilterIds +
                ", notGeneratedFilterIds=" + notGeneratedFilterIds +
                '}';
    }
}
