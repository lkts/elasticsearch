/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.rest.action.cat;

import org.elasticsearch.action.admin.indices.stats.CommonStats;
import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.health.ClusterIndexHealth;
import org.elasticsearch.cluster.health.ClusterStateHealth;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.project.TestProjectResolvers;
import org.elasticsearch.cluster.routing.GlobalRoutingTableTestHelper;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.Table;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.rest.FakeRestRequest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RestIndicesActionTests extends ESTestCase {

    public void testBuildTable() {
        final int numIndices = randomIntBetween(3, 20);
        final Map<String, Settings> indicesSettings = new LinkedHashMap<>();
        final Map<String, IndexStats> indicesStats = new HashMap<>();

        final ProjectMetadata.Builder projectBuilder = ProjectMetadata.builder(randomProjectIdOrDefault());
        final RoutingTable.Builder routingTable = RoutingTable.builder();

        for (int i = 0; i < numIndices; i++) {
            String indexName = "index-" + i;

            Settings indexSettings = Settings.builder()
                .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
                .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
                .build();
            indicesSettings.put(indexName, indexSettings);

            IndexMetadata.State indexState = randomBoolean() ? IndexMetadata.State.OPEN : IndexMetadata.State.CLOSE;
            if (frequently()) {
                ClusterHealthStatus healthStatus = randomFrom(ClusterHealthStatus.values());
                int numberOfShards = randomIntBetween(1, 3);
                int numberOfReplicas = healthStatus == ClusterHealthStatus.YELLOW ? 1 : randomInt(1);
                IndexMetadata indexMetadata = IndexMetadata.builder(indexName)
                    .settings(indexSettings)
                    .creationDate(System.currentTimeMillis())
                    .numberOfShards(numberOfShards)
                    .numberOfReplicas(numberOfReplicas)
                    .state(indexState)
                    .build();
                projectBuilder.put(indexMetadata, false);

                if (frequently()) {
                    Index index = indexMetadata.getIndex();
                    IndexRoutingTable.Builder indexRoutingTable = IndexRoutingTable.builder(index);
                    switch (randomFrom(ClusterHealthStatus.values())) {
                        case GREEN:
                            IntStream.range(0, numberOfShards)
                                .mapToObj(n -> new ShardId(index, n))
                                .map(shardId -> TestShardRouting.newShardRouting(shardId, "nodeA", true, ShardRoutingState.STARTED))
                                .forEach(indexRoutingTable::addShard);
                            if (numberOfReplicas > 0) {
                                IntStream.range(0, numberOfShards)
                                    .mapToObj(n -> new ShardId(index, n))
                                    .map(shardId -> TestShardRouting.newShardRouting(shardId, "nodeB", false, ShardRoutingState.STARTED))
                                    .forEach(indexRoutingTable::addShard);
                            }
                            break;
                        case YELLOW:
                            IntStream.range(0, numberOfShards)
                                .mapToObj(n -> new ShardId(index, n))
                                .map(shardId -> TestShardRouting.newShardRouting(shardId, "nodeA", true, ShardRoutingState.STARTED))
                                .forEach(indexRoutingTable::addShard);
                            if (numberOfReplicas > 0) {
                                IntStream.range(0, numberOfShards)
                                    .mapToObj(n -> new ShardId(index, n))
                                    .map(shardId -> TestShardRouting.newShardRouting(shardId, null, false, ShardRoutingState.UNASSIGNED))
                                    .forEach(indexRoutingTable::addShard);
                            }
                            break;
                        case RED:
                            break;
                    }
                    routingTable.add(indexRoutingTable);

                    if (frequently()) {
                        IndexStats indexStats = mock(IndexStats.class);
                        when(indexStats.getPrimaries()).thenReturn(new CommonStats());
                        when(indexStats.getTotal()).thenReturn(new CommonStats());
                        indicesStats.put(indexName, indexStats);
                    }
                }
            }
        }

        final ProjectMetadata project = projectBuilder.build();
        final ClusterState clusterState = ClusterState.builder(ClusterState.EMPTY_STATE)
            .metadata(Metadata.builder().put(project).build())
            .routingTable(GlobalRoutingTableTestHelper.routingTable(project.id(), randomBoolean() ? routingTable : RoutingTable.builder()))
            .build();

        final RestIndicesAction action = new RestIndicesAction(TestProjectResolvers.singleProject(project.id()));
        final Table table = action.buildTable(new FakeRestRequest(), indicesSettings, clusterState, indicesStats);

        // now, verify the table is correct
        List<Table.Cell> headers = table.getHeaders();
        assertThat(headers.get(0).value, equalTo("health"));
        assertThat(headers.get(1).value, equalTo("status"));
        assertThat(headers.get(2).value, equalTo("index"));
        assertThat(headers.get(3).value, equalTo("uuid"));
        assertThat(headers.get(4).value, equalTo("pri"));
        assertThat(headers.get(5).value, equalTo("rep"));

        final List<List<Table.Cell>> rows = table.getRows();
        assertThat(rows.size(), equalTo(project.indices().size()));

        final var clusterStateHealth = new ClusterStateHealth(clusterState, project.getConcreteAllIndices(), project.id());

        for (final List<Table.Cell> row : rows) {
            final String indexName = (String) row.get(2).value;

            ClusterIndexHealth indexHealth = clusterStateHealth.getIndices().get(indexName);
            IndexStats indexStats = indicesStats.get(indexName);
            IndexMetadata indexMetadata = project.index(indexName);

            if (indexHealth != null) {
                assertThat(row.get(0).value, equalTo(indexHealth.getStatus().toString().toLowerCase(Locale.ROOT)));
            } else if (indexStats != null) {
                assertThat(row.get(0).value, equalTo("red*"));
            } else {
                assertThat(row.get(0).value, equalTo(""));
            }

            assertThat(row.get(1).value, equalTo(indexMetadata.getState().toString().toLowerCase(Locale.ROOT)));
            assertThat(row.get(2).value, equalTo(indexName));
            assertThat(row.get(3).value, equalTo(indexMetadata.getIndexUUID()));
            assertThat(row.get(4).value, equalTo(indexMetadata.getNumberOfShards()));
            assertThat(row.get(5).value, equalTo(indexMetadata.getNumberOfReplicas()));
        }
    }

    public void testBuildTableFailsWithMultipleProjects() {
        final Metadata.Builder metadataBuilder = Metadata.builder();
        final int projectCount = randomIntBetween(2, 5);
        for (int i = 0; i < projectCount; i++) {
            metadataBuilder.put(ProjectMetadata.builder(randomUniqueProjectId()));
        }
        final Metadata metadata = metadataBuilder.build();
        final ClusterState clusterState = ClusterState.builder(ClusterState.EMPTY_STATE)
            .metadata(metadata)
            .routingTable(GlobalRoutingTableTestHelper.buildRoutingTable(metadata, RoutingTable.Builder::addAsNew))
            .build();

        final RestIndicesAction action = new RestIndicesAction(TestProjectResolvers.DEFAULT_PROJECT_ONLY);
        final IllegalStateException exception = expectThrows(
            IllegalStateException.class,
            () -> action.buildTable(new FakeRestRequest(), Map.of(), clusterState, Map.of())
        );
        assertThat(exception.getMessage(), containsString("multiple projects"));
    }
}
