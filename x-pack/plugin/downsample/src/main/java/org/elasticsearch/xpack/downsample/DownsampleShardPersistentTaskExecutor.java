/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.LegacyActionRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.mapper.TimeSeriesIdFieldMapper;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardNotFoundException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.downsample.DownsampleShardIndexerStatus;
import org.elasticsearch.xpack.core.downsample.DownsampleShardPersistentTaskState;
import org.elasticsearch.xpack.core.downsample.DownsampleShardTask;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

public class DownsampleShardPersistentTaskExecutor extends PersistentTasksExecutor<DownsampleShardTaskParams> {
    private static final Logger LOGGER = LogManager.getLogger(DownsampleShardPersistentTaskExecutor.class);
    private final Client client;
    private final boolean isStateless;

    public DownsampleShardPersistentTaskExecutor(final Client client, final String taskName, Settings settings, final Executor executor) {
        super(taskName, executor);
        this.client = Objects.requireNonNull(client);
        this.isStateless = DiscoveryNode.isStateless(settings);
    }

    @Override
    protected void nodeOperation(
        final AllocatedPersistentTask task,
        final DownsampleShardTaskParams params,
        final PersistentTaskState state
    ) {
        // NOTE: query the downsampling target index so that we can start the downsampling task from the latest indexed tsid.
        final SearchRequest searchRequest = new SearchRequest(params.downsampleIndex());
        searchRequest.source().sort(TimeSeriesIdFieldMapper.NAME, SortOrder.DESC).size(1);
        searchRequest.preference("_shards:" + params.shardId().id());
        client.search(searchRequest, ActionListener.wrap(searchResponse -> {
            delegate(task, params, extractTsId(searchResponse.getHits().getHits()));
        }, e -> delegate(task, params, null)));
    }

    private static BytesRef extractTsId(SearchHit[] lastDownsampleTsidHits) {
        if (lastDownsampleTsidHits.length == 0) {
            return null;
        } else {
            var searchHit = Arrays.stream(lastDownsampleTsidHits).findFirst().get();
            var field = searchHit.field("_tsid");
            return field != null ? field.getValue() : null;
        }
    }

    @Override
    protected AllocatedPersistentTask createTask(
        long id,
        final String type,
        final String action,
        final TaskId parentTaskId,
        final PersistentTasksCustomMetadata.PersistentTask<DownsampleShardTaskParams> taskInProgress,
        final Map<String, String> headers
    ) {
        final DownsampleShardTaskParams params = taskInProgress.getParams();
        return new DownsampleShardTask(
            id,
            type,
            action,
            parentTaskId,
            params.downsampleIndex(),
            params.indexStartTimeMillis(),
            params.indexEndTimeMillis(),
            params.downsampleConfig(),
            headers,
            params.shardId()
        );
    }

    @Override
    public void validate(DownsampleShardTaskParams params, ClusterState clusterState, @Nullable ProjectId projectId) {
        // This is just a pre-check, but doesn't prevent from avoiding from aborting the task when source index disappeared
        // after initial creation of the persistent task.
        var indexShardRouting = findShardRoutingTable(params.shardId(), clusterState);
        if (indexShardRouting == null) {
            throw new ShardNotFoundException(params.shardId());
        }
    }

    @Override
    protected PersistentTasksCustomMetadata.Assignment doGetAssignment(
        final DownsampleShardTaskParams params,
        final Collection<DiscoveryNode> candidateNodes,
        final ClusterState clusterState,
        @Nullable final ProjectId projectId
    ) {
        // NOTE: downsampling works by running a task per each shard of the source index.
        // Here we make sure we assign the task to the actual node holding the shard identified by
        // the downsampling task shard id.
        final ShardId shardId = params.shardId();

        // If during re-assignment the source index was deleted, then we need to break out.
        // Returning NO_NODE_FOUND just keeps the persistent task until the source index appears again (which would never happen)
        // So let's return a node and then in the node operation we would just fail and stop this persistent task
        var indexShardRouting = findShardRoutingTable(shardId, clusterState);
        if (indexShardRouting == null) {
            var node = selectLeastLoadedNode(clusterState, candidateNodes, DiscoveryNode::canContainData);
            return new PersistentTasksCustomMetadata.Assignment(node.getId(), "a node to fail and stop this persistent task");
        }

        // We find the nodes that hold the eligible shards.
        // If the current node of such a shard is a candidate node, then we assign the task there.
        // This code is inefficient, but we are relying on the laziness of the intermediate operations
        // and the assumption that the first shard we examine has high chances of being assigned to a candidate node.
        return indexShardRouting.activeShards()
            .stream()
            .filter(this::isEligible)
            .map(ShardRouting::currentNodeId)
            .filter(nodeId -> isCandidateNode(candidateNodes, nodeId))
            .findAny()
            .map(nodeId -> new PersistentTasksCustomMetadata.Assignment(nodeId, "downsampling using node holding shard [" + shardId + "]"))
            .orElse(NO_NODE_FOUND);
    }

    /**
     * Only shards that can be searched can be used as the source of a downsampling task.
     * For simplicity, in non-stateless deployments we use the primary shard.
     */
    private boolean isEligible(ShardRouting shardRouting) {
        return shardRouting.started() && (isStateless ? shardRouting.isSearchable() : shardRouting.primary());
    }

    private boolean isCandidateNode(Collection<DiscoveryNode> candidateNodes, String nodeId) {
        for (DiscoveryNode candidateNode : candidateNodes) {
            if (candidateNode.getId().equals(nodeId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Executor getExecutor() {
        // The delegate action forks to the a downsample thread:
        return EsExecutors.DIRECT_EXECUTOR_SERVICE;
    }

    private void delegate(final AllocatedPersistentTask task, final DownsampleShardTaskParams params, final BytesRef lastDownsampleTsid) {
        DownsampleShardTask downsampleShardTask = (DownsampleShardTask) task;
        client.execute(
            DelegatingAction.INSTANCE,
            new DelegatingAction.Request(downsampleShardTask, lastDownsampleTsid, params),
            ActionListener.wrap(empty -> {}, e -> {
                LOGGER.error("error while delegating", e);
                markAsFailed(downsampleShardTask, e);
            })
        );
    }

    private static IndexShardRoutingTable findShardRoutingTable(ShardId shardId, ClusterState clusterState) {
        var indexRoutingTable = clusterState.globalRoutingTable().indexRouting(clusterState.metadata(), shardId.getIndex());
        return indexRoutingTable.map(routingTable -> routingTable.shard(shardId.getId())).orElse(null);
    }

    static void realNodeOperation(
        Client client,
        IndicesService indicesService,
        DownsampleMetrics downsampleMetrics,
        DownsampleShardTask task,
        DownsampleShardTaskParams params,
        BytesRef lastDownsampledTsid
    ) {
        client.threadPool().executor(Downsample.DOWNSAMPLE_TASK_THREAD_POOL_NAME).execute(new AbstractRunnable() {
            @Override
            public void onFailure(Exception e) {
                markAsFailed(task, e);
            }

            @Override
            protected void doRun() throws Exception {
                final var initialState = new DownsampleShardPersistentTaskState(
                    DownsampleShardIndexerStatus.INITIALIZED,
                    lastDownsampledTsid
                );
                try {
                    final var downsampleShardIndexer = new DownsampleShardIndexer(
                        task,
                        client,
                        indicesService.indexServiceSafe(params.shardId().getIndex()),
                        downsampleMetrics,
                        params.shardId(),
                        params.downsampleIndex(),
                        params.downsampleConfig(),
                        params.metrics(),
                        params.labels(),
                        params.dimensions(),
                        initialState
                    );
                    downsampleShardIndexer.execute();
                    task.markAsCompleted();
                } catch (final DownsampleShardIndexerException e) {
                    if (e.isRetriable()) {
                        LOGGER.warn("Downsampling task [" + task.getPersistentTaskId() + " retriable failure [" + e.getMessage() + "]");
                        task.markAsLocallyAborted(e.getMessage());
                    } else {
                        LOGGER.error(
                            "Downsampling task [" + task.getPersistentTaskId() + " non retriable failure [" + e.getMessage() + "]"
                        );
                        markAsFailed(task, e);
                    }
                } catch (IndexNotFoundException e) {
                    LOGGER.error("Downsampling task [" + task.getPersistentTaskId() + " failing because source index not assigned");
                    markAsFailed(task, e);
                } catch (final Exception e) {
                    LOGGER.error("Downsampling task [" + task.getPersistentTaskId() + " non-retriable failure [" + e.getMessage() + "]");
                    markAsFailed(task, e);
                }
            }
        });
    }

    private static void markAsFailed(DownsampleShardTask task, Exception e) {
        task.setDownsampleShardIndexerStatus(DownsampleShardIndexerStatus.FAILED);
        task.updatePersistentTaskState(
            new DownsampleShardPersistentTaskState(DownsampleShardIndexerStatus.FAILED, null),
            ActionListener.running(() -> task.markAsFailed(e))
        );
    }

    // This is needed for FLS/DLS to work correctly. The _indices_permissions in the thread local aren't set if an searcher is acquired
    // directly from this persistent task executor. By delegating to this action (with a request that implements IndicesRequest) the
    // security thread local will be setup correctly so that we avoid this error:
    // org.elasticsearch.ElasticsearchSecurityException: no indices permissions found
    public static class DelegatingAction extends ActionType<ActionResponse.Empty> {

        public static final DelegatingAction INSTANCE = new DelegatingAction();
        public static final String NAME = "indices:data/read/downsample_delegate";

        private DelegatingAction() {
            super(NAME);
        }

        public static class Request extends LegacyActionRequest implements IndicesRequest.RemoteClusterShardRequest {

            private final DownsampleShardTask task;
            private final BytesRef lastDownsampleTsid;
            private final DownsampleShardTaskParams params;

            public Request(DownsampleShardTask task, BytesRef lastDownsampleTsid, DownsampleShardTaskParams params) {
                this.task = task;
                this.lastDownsampleTsid = lastDownsampleTsid;
                this.params = params;
            }

            @Override
            public ActionRequestValidationException validate() {
                return null;
            }

            @Override
            public String[] indices() {
                return new String[] { params.shardId().getIndexName() };
            }

            @Override
            public IndicesOptions indicesOptions() {
                return IndicesOptions.STRICT_EXPAND_OPEN;
            }

            @Override
            public void writeTo(StreamOutput out) {
                throw new IllegalStateException("request should stay local");
            }

            @Override
            public Collection<ShardId> shards() {
                return Collections.singletonList(task.shardId());
            }
        }

        public static class TA extends TransportAction<Request, ActionResponse.Empty> {

            private final Client client;
            private final IndicesService indicesService;
            private final DownsampleMetrics downsampleMetrics;

            @Inject
            public TA(
                TransportService transportService,
                ActionFilters actionFilters,
                Client client,
                IndicesService indicesService,
                DownsampleMetrics downsampleMetrics
            ) {
                // TODO: consider moving to Downsample.DOWSAMPLE_TASK_THREAD_POOL_NAME and simplify realNodeOperation
                super(NAME, actionFilters, transportService.getTaskManager(), EsExecutors.DIRECT_EXECUTOR_SERVICE);
                this.client = client;
                this.indicesService = indicesService;
                this.downsampleMetrics = downsampleMetrics;
            }

            @Override
            protected void doExecute(Task t, Request request, ActionListener<ActionResponse.Empty> listener) {
                realNodeOperation(client, indicesService, downsampleMetrics, request.task, request.params, request.lastDownsampleTsid);
                listener.onResponse(ActionResponse.Empty.INSTANCE);
            }

        }
    }
}
