/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.rollup.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.logging.DeprecationCategory;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.transport.Transports;
import org.elasticsearch.xpack.core.rollup.action.GetRollupCapsAction;
import org.elasticsearch.xpack.core.rollup.action.RollableIndexCaps;
import org.elasticsearch.xpack.core.rollup.action.RollupJobCaps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.rollup.Rollup.DEPRECATION_KEY;
import static org.elasticsearch.xpack.rollup.Rollup.DEPRECATION_MESSAGE;

public class TransportGetRollupCapsAction extends HandledTransportAction<GetRollupCapsAction.Request, GetRollupCapsAction.Response> {

    private static final DeprecationLogger DEPRECATION_LOGGER = DeprecationLogger.getLogger(TransportGetRollupCapsAction.class);

    private final ClusterService clusterService;
    private final Executor managementExecutor;
    private final ProjectResolver projectResolver;

    @Inject
    public TransportGetRollupCapsAction(
        TransportService transportService,
        ClusterService clusterService,
        ActionFilters actionFilters,
        ProjectResolver projectResolver
    ) {
        // TODO replace SAME when removing workaround for https://github.com/elastic/elasticsearch/issues/97916
        super(
            GetRollupCapsAction.NAME,
            transportService,
            actionFilters,
            GetRollupCapsAction.Request::new,
            EsExecutors.DIRECT_EXECUTOR_SERVICE
        );
        this.clusterService = clusterService;
        this.managementExecutor = transportService.getThreadPool().executor(ThreadPool.Names.MANAGEMENT);
        this.projectResolver = projectResolver;
    }

    @Override
    protected void doExecute(Task task, GetRollupCapsAction.Request request, ActionListener<GetRollupCapsAction.Response> listener) {
        DEPRECATION_LOGGER.warn(DeprecationCategory.API, DEPRECATION_KEY, DEPRECATION_MESSAGE);
        // Workaround for https://github.com/elastic/elasticsearch/issues/97916 - TODO remove this when we can
        managementExecutor.execute(ActionRunnable.wrap(listener, l -> doExecuteForked(request.getIndexPattern(), l)));
    }

    private void doExecuteForked(String indexPattern, ActionListener<GetRollupCapsAction.Response> listener) {
        Transports.assertNotTransportThread("retrieving rollup job caps may be expensive");
        final var project = projectResolver.getProjectMetadata(clusterService.state());
        Map<String, RollableIndexCaps> allCaps = getCaps(indexPattern, project.indices());
        listener.onResponse(new GetRollupCapsAction.Response(allCaps));
    }

    static Map<String, RollableIndexCaps> getCaps(String indexPattern, Map<String, IndexMetadata> indices) {
        Map<String, List<RollupJobCaps>> allCaps = new TreeMap<>();
        for (var entry : indices.entrySet()) {

            // Does this index have rollup metadata?
            TransportGetRollupCapsAction.findRollupIndexCaps(entry.getKey(), entry.getValue()).ifPresent(cap -> {

                List<RollupJobCaps> jobCaps;
                if (indexPattern.equals(Metadata.ALL)) {
                    // This index has rollup metadata, and since we want _all, just process all of them
                    jobCaps = cap.getJobCaps();
                } else {
                    // This index has rollup metadata, but is it for the index pattern that we're looking for?
                    jobCaps = cap.getJobCapsByIndexPattern(indexPattern);
                }

                jobCaps.forEach(jobCap -> {
                    String pattern = indexPattern.equals(Metadata.ALL) ? jobCap.getIndexPattern() : indexPattern;

                    // Do we already have an entry for this index pattern?
                    List<RollupJobCaps> indexCaps = allCaps.get(pattern);
                    if (indexCaps == null) {
                        indexCaps = new ArrayList<>();
                    }
                    indexCaps.add(jobCap);
                    allCaps.put(pattern, indexCaps);
                });
            });
        }

        // Convert the mutable lists into the RollableIndexCaps
        return allCaps.entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> new RollableIndexCaps(e.getKey(), e.getValue())));
    }

    static Optional<RollupIndexCaps> findRollupIndexCaps(String indexName, IndexMetadata indexMetadata) {
        if (indexMetadata == null) {
            return Optional.empty();
        }

        MappingMetadata rollupMapping = indexMetadata.mapping();
        if (rollupMapping == null) {
            return Optional.empty();
        }

        Object objMeta = rollupMapping.getSourceAsMap().get("_meta");
        if (objMeta == null) {
            return Optional.empty();
        }

        RollupIndexCaps caps = RollupIndexCaps.parseMetadataXContent(rollupMapping.source().uncompressed(), indexName);

        if (caps.hasCaps()) {
            return Optional.of(caps);
        }
        return Optional.empty();
    }
}
