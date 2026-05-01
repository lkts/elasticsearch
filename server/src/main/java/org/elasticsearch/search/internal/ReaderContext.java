/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.internal;

import org.elasticsearch.core.AbstractRefCounted;
import org.elasticsearch.core.RefCounted;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.search.RescoreDocIds;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.dfs.AggregatedDfs;
import org.elasticsearch.transport.TransportRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds a reference to a point in time {@link Engine.Searcher} that will be used to construct {@link SearchContext}.
 * This class also implements {@link RefCounted} since in some situations like
 * in {@link org.elasticsearch.search.SearchService} a SearchContext can be closed concurrently due to independent events
 * ie. when an index gets removed. To prevent accessing closed IndexReader / IndexSearcher instances the SearchContext
 * can be guarded by a reference count and fail if it's been closed by an external event.
 */
public interface ReaderContext extends Releasable {
    void validate(TransportRequest request);

    void addOnClose(Releasable releasable);

    ShardSearchContextId id();

    IndexService indexService();

    IndexShard indexShard();

    // TODO remove from the interface
    long keepAlive();

    Engine.Searcher acquireSearcher(String source);

    /**
     * Returns a releasable to indicate that the caller has stopped using this reader.
     * The time to live of the reader after usage can be extended using the provided
     * <code>keepAliveInMillis</code>.
     */
    Releasable markAsUsed(long keepAliveInMillis);

    boolean isExpired();

    // Remove from the interface.
    boolean isRelocating();

    /**
     * Indicate that this context is in the process of relocating.
     * We check this to prevent new search requests from using this context,
     * while running searches can still use it. Also this marks the context for cleanup
     * in one of the next {@link  SearchService} Reaper runs.
     */
    void relocate(); // TODO hack, remove

    ShardSearchRequest getShardSearchRequest(ShardSearchRequest other);

    ScrollContext scrollContext();

    AggregatedDfs getAggregatedDfs(AggregatedDfs other);

    void setAggregatedDfs(AggregatedDfs aggregatedDfs);

    RescoreDocIds getRescoreDocIds(RescoreDocIds other);

    void setRescoreDocIds(RescoreDocIds rescoreDocIds);

    /**
     * Returns {@code true} for readers that are intended to use in a single query. For readers that are intended
     * to use in multiple queries (i.e., scroll or readers), we should not release them after the fetch phase
     * or the query phase with empty results.
     */
    boolean singleSession();

    /**
     * Returns the object or <code>null</code> if the given key does not have a
     * value in the context
     */
    <T> T getFromContext(String key);

    /**
     * Puts the object into the context
     */
    void putInContext(String key, Object value);

    long getStartTimeInNano();

    public class SingleSessionReaderContext implements ReaderContext {
        private final ShardSearchContextId id;
        private final IndexService indexService;
        private final IndexShard indexShard;
        protected final Engine.SearcherSupplier searcherSupplier;
        private final AtomicLong keepAlive;
        private final AtomicLong lastAccessTime;
        // For reference why we use RefCounted here see https://github.com/elastic/elasticsearch/pull/20095.
        private final AbstractRefCounted refCounted;

        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final List<Releasable> onCloses = new CopyOnWriteArrayList<>();

        private final long startTimeInNano = System.nanoTime();

        private Map<String, Object> context;

        public SingleSessionReaderContext(
            ShardSearchContextId id,
            IndexService indexService,
            IndexShard indexShard,
            Engine.SearcherSupplier searcherSupplier,
            long keepAliveInMillis
        ) {
            this.id = id;
            this.indexService = indexService;
            this.indexShard = indexShard;
            this.searcherSupplier = searcherSupplier;
            this.keepAlive = new AtomicLong(keepAliveInMillis);
            this.lastAccessTime = new AtomicLong(nowInMillis());
            this.refCounted = AbstractRefCounted.of(this::doClose);
        }

        @Override
        public void validate(TransportRequest request) {
            indexShard.getSearchOperationListener().validateReaderContext(this, request);
        }

        @Override
        public void addOnClose(Releasable releasable) {
            onCloses.add(releasable);
        }

        @Override
        public ShardSearchContextId id() {
            return id;
        }

        @Override
        public IndexService indexService() {
            return indexService;
        }

        @Override
        public IndexShard indexShard() {
            return indexShard;
        }

        @Override
        public Engine.Searcher acquireSearcher(String source) {
            return searcherSupplier.acquireSearcher(source);
        }

        @Override
        public long keepAlive() {
            return keepAlive.longValue();
        }

        @Override
        public Releasable markAsUsed(long keepAliveInMillis) {
            refCounted.incRef();
            tryUpdateKeepAlive(keepAliveInMillis);
            return Releasables.releaseOnce(() -> {
                this.lastAccessTime.accumulateAndGet(nowInMillis(), Math::max);
                refCounted.decRef();
            });
        }

        @Override
        public boolean isExpired() {
            if (refCounted.refCount() > 1) {
                return false; // being used by markAsUsed
            }
            final long elapsed = nowInMillis() - lastAccessTime.get();
            return elapsed > keepAlive.get();
        }

        @Override
        public boolean isRelocating() {
            return false;
        }

        @Override
        public void relocate() {
            // TODO ????
            this.lastAccessTime.accumulateAndGet(nowInMillis(), Math::max);
        }

        // 6 methods below are (unfortunately) only implemented by ScrollReaderContext.

        @Override
        public ShardSearchRequest getShardSearchRequest(ShardSearchRequest other) {
            return Objects.requireNonNull(other, "ShardSearchRequest must be sent back in a fetch request");
        }

        @Override
        public ScrollContext scrollContext() {
            return null;
        }

        @Override
        public AggregatedDfs getAggregatedDfs(AggregatedDfs other) {
            return other;
        }

        @Override
        public void setAggregatedDfs(AggregatedDfs aggregatedDfs) {

        }

        @Override
        public RescoreDocIds getRescoreDocIds(RescoreDocIds other) {
            return Objects.requireNonNull(other, "RescoreDocIds must be sent back in a fetch request");
        }

        @Override
        public void setRescoreDocIds(RescoreDocIds rescoreDocIds) {

        }

        @Override
        public boolean singleSession() {
            return false;
        }

        @Override
        @SuppressWarnings("unchecked") // (T) object
        public <T> T getFromContext(String key) {
            return context != null ? (T) context.get(key) : null;
        }

        @Override
        public void putInContext(String key, Object value) {
            if (context == null) {
                context = new HashMap<>();
            }
            context.put(key, value);
        }

        @Override
        public long getStartTimeInNano() {
            return startTimeInNano;
        }

        @Override
        public final void close() {
            if (closed.compareAndSet(false, true)) {
                refCounted.decRef();
            }
        }

        private void doClose() {
            Releasables.close(Releasables.wrap(onCloses), searcherSupplier);
        }

        private long nowInMillis() {
            return indexShard.getThreadPool().relativeTimeInMillis();
        }

        private void tryUpdateKeepAlive(long keepAlive) {
            this.keepAlive.accumulateAndGet(keepAlive, Math::max);
        }
    }
}
