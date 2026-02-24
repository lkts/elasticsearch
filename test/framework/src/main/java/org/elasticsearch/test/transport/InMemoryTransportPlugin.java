/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.transport;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.plugins.NetworkPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Plugin that registers the {@link InMemoryTransport} under the name {@value InMemoryTransport#TRANSPORT_NAME}.
 * <p>
 * To use it in an integration test, add this plugin to the node plugins and set
 * {@code transport.type} to {@value InMemoryTransport#TRANSPORT_NAME} in node settings:
 * <pre>{@code
 * @Override
 * protected Collection<Class<? extends Plugin>> nodePlugins() {
 *     var plugins = new ArrayList<>(super.nodePlugins());
 *     plugins.add(InMemoryTransportPlugin.class);
 *     return plugins;
 * }
 *
 * // in node settings:
 * builder.put(NetworkModule.TRANSPORT_TYPE_KEY, InMemoryTransport.TRANSPORT_NAME);
 * }</pre>
 * The Netty4 plugin remains loaded (providing HTTP transport) but the inter-node transport
 * is handled entirely in-memory.
 */
public class InMemoryTransportPlugin extends Plugin implements NetworkPlugin {

    @Override
    public Map<String, Supplier<Transport>> getTransports(
        Settings settings,
        ThreadPool threadPool,
        PageCacheRecycler pageCacheRecycler,
        CircuitBreakerService circuitBreakerService,
        NamedWriteableRegistry namedWriteableRegistry,
        NetworkService networkService
    ) {
        return Map.of(InMemoryTransport.TRANSPORT_NAME, () -> new InMemoryTransport(threadPool, namedWriteableRegistry));
    }
}
