/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.datastreams.rest;

import org.elasticsearch.action.datastreams.DeleteDataStreamAction;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestUtils;
import org.elasticsearch.rest.Scope;
import org.elasticsearch.rest.ServerlessScope;
import org.elasticsearch.rest.action.RestToXContentListener;

import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.DELETE;

@ServerlessScope(Scope.PUBLIC)
public class RestDeleteDataStreamAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "delete_data_stream_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(DELETE, "/_data_stream/{name}"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        DeleteDataStreamAction.Request deleteDataStreamRequest = new DeleteDataStreamAction.Request(
            RestUtils.getMasterNodeTimeout(request),
            Strings.splitStringByCommaToArray(request.param("name"))
        );
        deleteDataStreamRequest.indicesOptions(IndicesOptions.fromRequest(request, deleteDataStreamRequest.indicesOptions()));
        return channel -> client.execute(DeleteDataStreamAction.INSTANCE, deleteDataStreamRequest, new RestToXContentListener<>(channel));
    }
}
