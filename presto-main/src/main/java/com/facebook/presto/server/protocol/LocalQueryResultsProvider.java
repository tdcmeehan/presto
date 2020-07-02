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
import com.facebook.airlift.log.Logger;
import com.facebook.presto.Session;
import com.facebook.presto.client.QueryResults;
import com.facebook.presto.common.block.BlockEncodingSerde;
import com.facebook.presto.execution.QueryManager;
import com.facebook.presto.memory.context.SimpleLocalMemoryContext;
import com.facebook.presto.operator.ExchangeClient;
import com.facebook.presto.operator.ExchangeClientSupplier;
import com.facebook.presto.server.ForStatementResource;
import com.facebook.presto.spi.QueryId;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.ws.rs.core.UriInfo;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;

import static com.facebook.airlift.concurrent.Threads.threadsNamed;
import static com.facebook.presto.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class LocalQueryResultsProvider
{
    private static final Logger log = Logger.get(LocalQueryResultsProvider.class);
    private static final Duration MAX_WAIT_TIME = new Duration(1, SECONDS);
    private static final Ordering<Comparable<Duration>> WAIT_ORDERING = Ordering.natural().nullsLast();

    private static final DataSize DEFAULT_TARGET_RESULT_SIZE = new DataSize(1, MEGABYTE);
    private static final DataSize MAX_TARGET_RESULT_SIZE = new DataSize(128, MEGABYTE);

    private final QueryManager queryManager;
    private final ExchangeClientSupplier exchangeClientSupplier;
    private final BlockEncodingSerde blockEncodingSerde;
    private final BoundedExecutor responseExecutor;
    private final ScheduledExecutorService timeoutExecutor;

    private final ConcurrentMap<QueryId, Query> queries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService queryPurger = newSingleThreadScheduledExecutor(threadsNamed("execution-query-purger"));

    @Inject
    public LocalQueryResultsProvider(
            QueryManager queryManager,
            ExchangeClientSupplier exchangeClientSupplier,
            BlockEncodingSerde blockEncodingSerde,
            @ForStatementResource BoundedExecutor responseExecutor,
            @ForStatementResource ScheduledExecutorService timeoutExecutor)
    {
        this.queryManager = requireNonNull(queryManager, "queryManager is null");
        this.exchangeClientSupplier = requireNonNull(exchangeClientSupplier, "exchangeClientSupplier is null");
        this.blockEncodingSerde = requireNonNull(blockEncodingSerde, "blockEncodingSerde is null");
        this.responseExecutor = requireNonNull(responseExecutor, "responseExecutor is null");
        this.timeoutExecutor = requireNonNull(timeoutExecutor, "timeoutExecutor is null");
    }

    @PostConstruct
    public void start()
    {
        queryPurger.scheduleWithFixedDelay(
                () -> {
                    try {
                        for (Map.Entry<QueryId, Query> entry : queries.entrySet()) {
                            // forget about this query if the query manager is no longer tracking it
                            try {
                                queryManager.getQueryState(entry.getKey());
                            }
                            catch (NoSuchElementException e) {
                                // query is no longer registered
                                queries.remove(entry.getKey());
                            }
                        }
                    }
                    catch (Throwable e) {
                        log.warn(e, "Error removing old queries");
                    }
                },
                200,
                200,
                MILLISECONDS);
    }

    @PreDestroy
    public void stop()
    {
        queryPurger.shutdownNow();
    }

    public ListenableFuture<QueryWithResults> fetchQueryResults(
            QueryId queryId,
            long token,
            String slug,
            Duration maxWait,
            DataSize targetResultSize,
            String proto,
            UriInfo uriInfo)
    {
        Query query;
        try {
            query = getQuery(queryId, slug);
        }
        catch (Exception e) {
            return immediateFailedFuture(e);
        }
        if (isNullOrEmpty(proto)) {
            proto = uriInfo.getRequestUri().getScheme();
        }

        return getQueryResults(query, token, maxWait, targetResultSize, uriInfo, proto);
    }

    private Query getQuery(QueryId queryId, String slug)
            throws NoSuchElementException
    {
        Query query = queries.get(queryId);
        if (query != null) {
            if (!query.isSlugValid(slug)) {
                throw new NoSuchElementException("Query not found");
            }
            return query;
        }

        // this is the first time the query has been accessed on this coordinator
        Session session;
        try {
            if (!queryManager.isQuerySlugValid(queryId, slug)) {
                throw new NoSuchElementException("Query not found");
            }
            session = queryManager.getQuerySession(queryId);
        }
        catch (NoSuchElementException e) {
            throw new NoSuchElementException("Query not found");
        }

        query = queries.computeIfAbsent(queryId, id -> {
            ExchangeClient exchangeClient = exchangeClientSupplier.get(new SimpleLocalMemoryContext(newSimpleAggregatedMemoryContext(), LocalQueryResultsProvider.class.getSimpleName()));
            return Query.create(
                    session,
                    slug,
                    queryManager,
                    exchangeClient,
                    responseExecutor,
                    timeoutExecutor,
                    blockEncodingSerde);
        });
        return query;
    }

    private ListenableFuture<QueryWithResults> getQueryResults(
            Query query,
            long token,
            Duration maxWait,
            DataSize targetResultSize,
            UriInfo uriInfo,
            String scheme)
    {
        Duration wait = WAIT_ORDERING.min(MAX_WAIT_TIME, maxWait);
        if (targetResultSize == null) {
            targetResultSize = DEFAULT_TARGET_RESULT_SIZE;
        }
        else {
            targetResultSize = Ordering.natural().min(targetResultSize, MAX_TARGET_RESULT_SIZE);
        }
        ListenableFuture<QueryResults> queryResultsFuture = query.waitForResults(token, uriInfo, scheme, wait, targetResultSize);

        return transform(
                queryResultsFuture,
                queryResults -> new QueryWithResults(query, queryResults),
                directExecutor());
    }

    public void cancelQuery(QueryId queryId, String slug)
    {
        Query query = queries.get(queryId);
        if (query != null) {
            if (!query.isSlugValid(slug)) {
                throw new NoSuchElementException("Query not found");
            }
            query.cancel();
        }

        // cancel the query execution directly instead of creating the statement client
        try {
            if (!queryManager.isQuerySlugValid(queryId, slug)) {
                throw new NoSuchElementException("Query not found");
            }
            queryManager.cancelQuery(queryId);
        }
        catch (NoSuchElementException e) {
            throw new NoSuchElementException("Query not found");
        }
    }

    public static final class QueryWithResults
    {
        private final Query query;
        private final QueryResults queryResults;

        private QueryWithResults(Query query, QueryResults queryResults)
        {
            this.query = requireNonNull(query, "query is null");
            this.queryResults = requireNonNull(queryResults, "queryResults is null");
        }

        public Query getQuery()
        {
            return query;
        }

        public QueryResults getQueryResults()
        {
            return queryResults;
        }
    }
}
