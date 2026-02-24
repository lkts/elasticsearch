/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.transport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.CloseableConnection;
import org.elasticsearch.transport.ConnectTransportException;
import org.elasticsearch.transport.ConnectionProfile;
import org.elasticsearch.transport.NodeNotConnectedException;
import org.elasticsearch.transport.RemoteTransportException;
import org.elasticsearch.transport.RequestHandlerRegistry;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportMessageListener;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportStats;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.elasticsearch.test.ESTestCase.copyWriteable;

/**
 * A purely in-memory {@link Transport} implementation for use in internal cluster tests.
 * Nodes find each other via a static registry keyed by {@link TransportAddress}. Messages
 * are delivered by serializing the request via {@code copyWriteable()} (preserving wire
 * protocol fidelity) and dispatching directly to the target node's request handler.
 */
public class InMemoryTransport extends AbstractLifecycleComponent implements Transport {

    public static final String TRANSPORT_NAME = "inmemory";

    private static final Logger logger = LogManager.getLogger(InMemoryTransport.class);
    private static final AtomicInteger PORT_COUNTER = new AtomicInteger(30000);
    private static final ConcurrentMap<TransportAddress, InMemoryTransport> REGISTRY = new ConcurrentHashMap<>();

    private final ResponseHandlers responseHandlers = new ResponseHandlers();
    private final RequestHandlers requestHandlers = new RequestHandlers();
    private volatile TransportMessageListener messageListener = TransportMessageListener.NOOP_LISTENER;

    private final ThreadPool threadPool;
    private final NamedWriteableRegistry namedWriteableRegistry;
    private volatile TransportAddress localAddress;
    private volatile BoundTransportAddress boundTransportAddress;

    public InMemoryTransport(ThreadPool threadPool, NamedWriteableRegistry namedWriteableRegistry) {
        this.threadPool = threadPool;
        this.namedWriteableRegistry = namedWriteableRegistry;
    }

    @Override
    protected void doStart() {
        localAddress = new TransportAddress(InetAddress.getLoopbackAddress(), PORT_COUNTER.incrementAndGet());
        boundTransportAddress = new BoundTransportAddress(new TransportAddress[] { localAddress }, localAddress);
        REGISTRY.put(localAddress, this);
    }

    @Override
    protected void doStop() {
        REGISTRY.remove(localAddress);
    }

    @Override
    protected void doClose() {}

    @Override
    public void setMessageListener(TransportMessageListener listener) {
        this.messageListener = listener;
    }

    @Override
    public BoundTransportAddress boundAddress() {
        return boundTransportAddress;
    }

    @Override
    public BoundTransportAddress boundRemoteIngressAddress() {
        return null;
    }

    @Override
    public Map<String, BoundTransportAddress> profileBoundAddresses() {
        return Collections.emptyMap();
    }

    @Override
    public TransportAddress[] addressesFromString(String address) throws UnknownHostException {
        int lastColon = address.lastIndexOf(':');
        if (lastColon < 0) {
            throw new UnknownHostException("invalid address format, expected host:port but got: " + address);
        }
        String host = address.substring(0, lastColon);
        int port = Integer.parseInt(address.substring(lastColon + 1));
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return new TransportAddress[] { new TransportAddress(InetAddress.getByName(host), port) };
    }

    @Override
    public List<String> getDefaultSeedAddresses() {
        return REGISTRY.keySet().stream().map(addr -> addr.address().getHostString() + ":" + addr.address().getPort()).toList();
    }

    @Override
    public void openConnection(DiscoveryNode node, ConnectionProfile profile, ActionListener<Connection> listener) {
        final InMemoryTransport target = REGISTRY.get(node.getAddress());
        if (target == null) {
            listener.onFailure(
                new ConnectTransportException(node, "node [" + node + "] not found in in-memory transport registry")
            );
            return;
        }
        listener.onResponse(new InMemoryConnection(node, target));
    }

    @Override
    public TransportStats getStats() {
        return new TransportStats(0, 0, 0, 0, 0, 0, new long[0], new long[0], Map.of());
    }

    @Override
    public ResponseHandlers getResponseHandlers() {
        return responseHandlers;
    }

    @Override
    public RequestHandlers getRequestHandlers() {
        return requestHandlers;
    }

    private class InMemoryConnection extends CloseableConnection {
        private final DiscoveryNode node;
        private final InMemoryTransport targetTransport;

        InMemoryConnection(DiscoveryNode node, InMemoryTransport targetTransport) {
            this.node = node;
            this.targetTransport = targetTransport;
        }

        @Override
        public DiscoveryNode getNode() {
            return node;
        }

        @Override
        public TransportVersion getTransportVersion() {
            return TransportVersion.current();
        }

        @Override
        public void sendRequest(long requestId, String action, TransportRequest request, TransportRequestOptions options)
            throws IOException, TransportException {
            if (isClosed()) {
                throw new NodeNotConnectedException(node, "connection closed");
            }

            final RequestHandlerRegistry<TransportRequest> handler = targetTransport.requestHandlers.getHandler(action);
            if (handler == null) {
                throw new TransportException("no request handler registered for action [" + action + "]");
            }

            final TransportRequest copiedRequest;
            try {
                copiedRequest = copyWriteable(request, namedWriteableRegistry, handler::newRequest);
                // copiedRequest = handler.newRequest()
            } catch (IOException e) {
                throw new AssertionError("failed to serialize/deserialize request for action [" + action + "]", e);
            }

            messageListener.onRequestSent(node, requestId, action, request, options);

            final TransportChannel channel = new InMemoryTransportChannel(requestId, action);

            final Runnable handleRequest = new Runnable() {
                @Override
                public void run() {
                    targetTransport.messageListener.onRequestReceived(requestId, action);
                    try {
                        handler.processMessageReceived(copiedRequest, channel);
                    } catch (Exception e) {
                        try {
                            channel.sendResponse(e);
                        } catch (Exception inner) {
                            logger.warn("failed to send error response for action [{}]", action, inner);
                        }
                    } finally {
                        copiedRequest.decRef();
                    }
                }

                @Override
                public String toString() {
                    return "in-memory request [" + requestId + "][" + action + "] from " + localAddress + " to " + node;
                }
            };

            final Executor executor = handler.getExecutor();
            if (executor == EsExecutors.DIRECT_EXECUTOR_SERVICE) {
                targetTransport.threadPool.generic().execute(handleRequest);
            } else {
                executor.execute(handleRequest);
            }
        }

        /**
         * Channel that delivers responses from the target node back to the sending node's
         * {@link ResponseHandlers}.
         */
        private class InMemoryTransportChannel implements TransportChannel {
            private final long requestId;
            private final String action;

            InMemoryTransportChannel(long requestId, String action) {
                this.requestId = requestId;
                this.action = action;
            }

            @Override
            public String getProfileName() {
                return "default";
            }

            @Override
            @SuppressWarnings("unchecked")
            public void sendResponse(TransportResponse response) {
                response.mustIncRef();
                final var releasable = Releasables.assertOnce(response::decRef);
                threadPool.generic().execute(new Runnable() {
                    @Override
                    public void run() {
                        try (releasable) {
                            final ResponseContext<? extends TransportResponse> ctx = responseHandlers.remove(requestId);
                            if (ctx != null) {
                                messageListener.onResponseReceived(requestId, ctx);
                                // There may be custom logic for response processing, we can't just skip that.
                                TransportResponse newResponse = null;
                                try {
                                    newResponse = ESTestCase.copyWriteable(response, namedWriteableRegistry, si -> ctx.handler().read(si));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                ((TransportResponseHandler<TransportResponse>) ctx.handler()).handleResponse(newResponse);
                            }
                        }
                    }

                    @Override
                    public String toString() {
                        return "in-memory response [" + requestId + "][" + action + "]";
                    }
                });
            }

            @Override
            public void sendResponse(Exception exception) {
                threadPool.generic().execute(new Runnable() {
                    @Override
                    public void run() {
                        final ResponseContext<? extends TransportResponse> ctx = responseHandlers.remove(requestId);
                        if (ctx != null) {
                            messageListener.onResponseReceived(requestId, ctx);
                            ctx.handler()
                                .handleException(
                                    new RemoteTransportException(node.getName(), node.getAddress(), action, exception)
                                );
                        }
                    }

                    @Override
                    public String toString() {
                        return "in-memory error response [" + requestId + "][" + action + "]";
                    }
                });
            }
        }
    }
}
