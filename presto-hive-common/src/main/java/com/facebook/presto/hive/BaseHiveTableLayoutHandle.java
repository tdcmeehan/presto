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

import com.facebook.presto.common.Subfield;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.relation.RowExpression;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static java.util.Objects.requireNonNull;

public class BaseHiveTableLayoutHandle
        implements ConnectorTableLayoutHandle
{
    private final TupleDomain<Subfield> domainPredicate;
    private final RowExpression remainingPredicate;
    private final boolean pushdownFilterEnabled;

    @JsonCreator
    public BaseHiveTableLayoutHandle(
            @JsonProperty("domainPredicate") TupleDomain<Subfield> domainPredicate,
            @JsonProperty("remainingPredicate") RowExpression remainingPredicate,
            @JsonProperty("pushdownFilterEnabled") boolean pushdownFilterEnabled)
    {
        this.domainPredicate = requireNonNull(domainPredicate, "domainPredicate is null");
        this.remainingPredicate = requireNonNull(remainingPredicate, "remainingPredicate is null");
        this.pushdownFilterEnabled = pushdownFilterEnabled;
    }

    @JsonProperty
    public TupleDomain<Subfield> getDomainPredicate()
    {
        return domainPredicate;
    }

    @JsonProperty
    public RowExpression getRemainingPredicate()
    {
        return remainingPredicate;
    }

    @JsonProperty
    public boolean isPushdownFilterEnabled()
    {
        return pushdownFilterEnabled;
    }
}
