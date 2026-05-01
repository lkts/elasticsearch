/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.internal;

import org.elasticsearch.core.Releasable;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.search.RescoreDocIds;
import org.elasticsearch.search.dfs.AggregatedDfs;
import org.elasticsearch.transport.TransportRequest;

import java.util.Objects;

public final class ScrollReaderContext implements ReaderContext {
    private final ReaderContext delegate;

    private final ShardSearchRequest shardSearchRequest;
    private final ScrollContext scrollContext;

    private AggregatedDfs aggregatedDfs;
    private RescoreDocIds rescoreDocIds;

    public ScrollReaderContext(
        ShardSearchContextId id,
        IndexService indexService,
        IndexShard indexShard,
        Engine.SearcherSupplier searcherSupplier,
        ShardSearchRequest shardSearchRequest,
        long keepAliveInMillis
    ) {
        assert shardSearchRequest.readerId() == null;
        assert shardSearchRequest.keepAlive() == null;
        assert id.isRetryable() == false : "Legacy reader context is not retryable";
        assert shardSearchRequest.scroll() != null;

        this.shardSearchRequest = Objects.requireNonNull(shardSearchRequest, "ShardSearchRequest must be provided");
        this.scrollContext = new ScrollContext();

        // Search scroll requests are special, they don't hold indices names so we have
        // to reuse the searcher created on the request that initialized the scroll.
        // This ensures that we wrap the searcher's reader with the user's permissions
        // when they are available.
        final Engine.Searcher searcher = searcherSupplier.acquireSearcher("search");
        // wrap the searcher so that closing is a noop, the actual closing happens when this context is closed
        var wrappedSearcher = new Engine.Searcher(
            searcher.source(),
            searcher.getDirectoryReader(),
            searcher.getSimilarity(),
            searcher.getQueryCache(),
            searcher.getQueryCachingPolicy(),
            () -> {}
        );
        var supplier = new Engine.SearcherSupplier(indexShard::wrapSearcher) {
            @Override
            protected void doClose() {
                searcher.close();
            }

            @Override
            protected Engine.Searcher acquireSearcherInternal(String source) {
                return wrappedSearcher;
            }
        };

        this.delegate = new SingleSessionReaderContext(id, indexService, indexShard, supplier, keepAliveInMillis);
    }

    @Override
    public void validate(TransportRequest request) {
        delegate.validate(request);
    }

    @Override
    public void addOnClose(Releasable releasable) {
        delegate.addOnClose(releasable);
    }

    @Override
    public ShardSearchContextId id() {
        return delegate.id();
    }

    @Override
    public IndexService indexService() {
        return delegate.indexService();
    }

    @Override
    public IndexShard indexShard() {
        return delegate.indexShard();
    }

    @Override
    public long keepAlive() {
        return delegate.keepAlive();
    }

    @Override
    public Engine.Searcher acquireSearcher(String source) {
        assert Engine.SEARCH_SOURCE.equals(source) : "scroll context should not acquire searcher for " + source;
        return delegate.acquireSearcher(source);
    }

    @Override
    public Releasable markAsUsed(long keepAliveInMillis) {
        return delegate.markAsUsed(keepAliveInMillis);
    }

    @Override
    public boolean isExpired() {
        return delegate.isExpired();
    }

    @Override
    public boolean isRelocating() {
        return false;
    }

    @Override
    public void relocate() {
        delegate.relocate();
    }

    @Override
    public ShardSearchRequest getShardSearchRequest(ShardSearchRequest other) {
        if (other != null) {
            // The top level knn search modifies the source after the DFS phase.
            // so we need to update the source stored in the context.
            shardSearchRequest.source(other.source());
        }
        return shardSearchRequest;
    }

    @Override
    public ScrollContext scrollContext() {
        return scrollContext;
    }

    @Override
    public AggregatedDfs getAggregatedDfs(AggregatedDfs other) {
        return aggregatedDfs;
    }

    @Override
    public void setAggregatedDfs(AggregatedDfs aggregatedDfs) {
        this.aggregatedDfs = aggregatedDfs;
    }

    @Override
    public RescoreDocIds getRescoreDocIds(RescoreDocIds other) {
        return rescoreDocIds;
    }

    @Override
    public void setRescoreDocIds(RescoreDocIds rescoreDocIds) {
        this.rescoreDocIds = rescoreDocIds;
    }

    @Override
    public boolean singleSession() {
        return false;
    }

    @Override
    public <T> T getFromContext(String key) {
        return delegate.getFromContext(key);
    }

    @Override
    public void putInContext(String key, Object value) {
        delegate.putInContext(key, value);
    }

    @Override
    public long getStartTimeInNano() {
        return delegate.getStartTimeInNano();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
