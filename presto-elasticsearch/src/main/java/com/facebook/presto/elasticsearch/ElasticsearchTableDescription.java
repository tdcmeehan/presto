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
package com.facebook.presto.elasticsearch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

public class ElasticsearchTableDescription
{
    private final String tableName;
    private final String schemaName;
    private final String index;
    private final String type;
    private final Optional<List<ElasticsearchColumn>> columns;

    @JsonCreator
    public ElasticsearchTableDescription(
            @JsonProperty("tableName") String tableName,
            @JsonProperty("schemaName") String schemaName,
            @JsonProperty("index") String index,
            @JsonProperty("type") String type,
            @JsonProperty("columns") Optional<List<ElasticsearchColumn>> columns)
    {
        checkArgument(!isNullOrEmpty(tableName), "tableName is null or empty");
        checkArgument(!isNullOrEmpty(schemaName), "schemaName is null or empty");
        checkArgument(!isNullOrEmpty(index), "index is null or empty");
        checkArgument(!isNullOrEmpty(type), "type is null or empty");
        requireNonNull(columns, "columns is null");
        this.tableName = tableName;
        this.schemaName = schemaName;
        this.index = index;
        this.type = type;
        this.columns = columns;
    }

    @JsonProperty
    public String getTableName()
    {
        return tableName;
    }

    @JsonProperty
    public String getSchemaName()
    {
        return schemaName;
    }

    @JsonProperty
    public String getIndex()
    {
        return index;
    }

    @JsonProperty
    public String getType()
    {
        return type;
    }

    @JsonProperty
    public Optional<List<ElasticsearchColumn>> getColumns()
    {
        return columns;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("tableName", tableName)
                .add("schemaName", schemaName)
                .add("index", index)
                .add("type", type)
                .add("columns", columns)
                .toString();
    }
}
