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
package com.facebook.presto.server.protocol;

import com.facebook.airlift.concurrent.BoundedExecutor;
import com.facebook.presto.client.QueryResults;
import com.facebook.presto.server.ForStatementResource;
import com.facebook.presto.spi.QueryId;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;

import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import static com.facebook.airlift.http.server.AsyncResponseHandler.bindAsyncResponse;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_ADDED_PREPARE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_CLEAR_SESSION;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_CLEAR_TRANSACTION_ID;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_DEALLOCATED_PREPARE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SET_CATALOG;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SET_ROLE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SET_SCHEMA;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SET_SESSION;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_STARTED_TRANSACTION_ID;
import static com.google.common.net.HttpHeaders.X_FORWARDED_PROTO;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;

@Path("/")
public class ExecutingStatementResource
{
    private final BoundedExecutor responseExecutor;
    private final LocalQueryResultsProvider queryExecutor;

    @Inject
    public ExecutingStatementResource(
            @ForStatementResource BoundedExecutor responseExecutor,
            LocalQueryResultsProvider queryExecutor)
    {
        this.responseExecutor = requireNonNull(responseExecutor, "responseExecutor is null");
        this.queryExecutor = requireNonNull(queryExecutor, "queryExecutor is null");
    }

    @GET
    @Path("/v1/statement/executing/{queryId}/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    public void getQueryResults(
            @PathParam("queryId") QueryId queryId,
            @PathParam("token") long token,
            @QueryParam("slug") String slug,
            @QueryParam("maxWait") Duration maxWait,
            @QueryParam("targetResultSize") DataSize targetResultSize,
            @HeaderParam(X_FORWARDED_PROTO) String proto,
            @Context UriInfo uriInfo,
            @Suspended AsyncResponse asyncResponse)
    {
        ListenableFuture<LocalQueryResultsProvider.QueryWithResults> queryResultsFuture = queryExecutor.fetchQueryResults(queryId, token, slug, maxWait, targetResultSize, proto, uriInfo);
        ListenableFuture<Response> response = Futures.transform(queryResultsFuture, queryWithResults -> toResponse(queryWithResults.getQuery(), queryWithResults.getQueryResults()), directExecutor());
        bindAsyncResponse(asyncResponse, response, responseExecutor);
    }

    private static Response toResponse(Query query, QueryResults queryResults)
    {
        ResponseBuilder response = Response.ok(queryResults);

        // add set catalog and schema
        query.getSetCatalog().ifPresent(catalog -> response.header(PRESTO_SET_CATALOG, catalog));
        query.getSetSchema().ifPresent(schema -> response.header(PRESTO_SET_SCHEMA, schema));

        // add set session properties
        query.getSetSessionProperties()
                .forEach((key, value) -> response.header(PRESTO_SET_SESSION, key + '=' + urlEncode(value)));

        // add clear session properties
        query.getResetSessionProperties()
                .forEach(name -> response.header(PRESTO_CLEAR_SESSION, name));

        // add set roles
        query.getSetRoles()
                .forEach((key, value) -> response.header(PRESTO_SET_ROLE, key + '=' + urlEncode(value.toString())));

        // add added prepare statements
        for (Entry<String, String> entry : query.getAddedPreparedStatements().entrySet()) {
            String encodedKey = urlEncode(entry.getKey());
            String encodedValue = urlEncode(entry.getValue());
            response.header(PRESTO_ADDED_PREPARE, encodedKey + '=' + encodedValue);
        }

        // add deallocated prepare statements
        for (String name : query.getDeallocatedPreparedStatements()) {
            response.header(PRESTO_DEALLOCATED_PREPARE, urlEncode(name));
        }

        // add new transaction ID
        query.getStartedTransactionId()
                .ifPresent(transactionId -> response.header(PRESTO_STARTED_TRANSACTION_ID, transactionId));

        // add clear transaction ID directive
        if (query.isClearTransactionId()) {
            response.header(PRESTO_CLEAR_TRANSACTION_ID, true);
        }

        return response.build();
    }

    @DELETE
    @Path("/v1/statement/executing/{queryId}/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelQuery(
            @PathParam("queryId") QueryId queryId,
            @PathParam("token") long token,
            @QueryParam("slug") String slug)
    {
        try {
            queryExecutor.cancelQuery(queryId, slug);
            return Response.noContent().build();
        }
        catch (NoSuchElementException e) {
            throw notFound("Query not found");
        }
    }

    private static WebApplicationException notFound(String message)
    {
        throw new WebApplicationException(
                Response.status(Status.NOT_FOUND)
                        .type(TEXT_PLAIN_TYPE)
                        .entity(message)
                        .build());
    }

    private static String urlEncode(String value)
    {
        try {
            return URLEncoder.encode(value, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
