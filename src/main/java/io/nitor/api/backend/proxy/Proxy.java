/**
 * Copyright 2016-2017 Nitor Creations Oy, Jonas Berlin
 *
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
package io.nitor.api.backend.proxy;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.VertxException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.HeadersAdaptor;
import io.vertx.core.http.impl.WebSocketHandshakeRejectedException;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.streams.Pump;
import io.vertx.ext.web.RoutingContext;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static io.vertx.core.http.HttpVersion.HTTP_2;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Reverse HTTP proxy, ported to Java from https://github.com/xkr47/vhostproxy4/
 */
public class Proxy implements Handler<RoutingContext> {

    private final HttpClient client;
    private final TargetResolver targetResolver;
    private final String keepAliveHeaderValue;
    private final int clientReceiveTimeout;
    private final Supplier<ProxyTracer> tracerFactory;

    public interface TargetResolver {
        /**
         * Resole the next hop to go to, if any. Should eventually call exactly one of these:
         * <ul>
         * <li><tt>targetHandler.handle(target);</tt></li>
         * <li><tt>routingContext.next();</tt></li>
         * <li><tt>routingContext.fail(...);</tt></li>
         * </ul>
         *
         * @param routingContext the routingContext to resolve target for
         * @param targetHandler  the handler to notify with the target, unless the request has already been handled, in which case it must not be called.
         */
        void resolveNextHop(RoutingContext routingContext, Handler<Target> targetHandler);
    }

    public Proxy(HttpClient client, TargetResolver targetResolver, int serverIdleTimeout, int clientReceiveTimeout, Supplier<ProxyTracer> tracerFactory) {
        this.client = client;
        this.targetResolver = targetResolver;
        this.keepAliveHeaderValue = "timeout=" + (serverIdleTimeout - 5);
        this.clientReceiveTimeout = clientReceiveTimeout;
        this.tracerFactory = tracerFactory;
    }

    public static class Target {
        public final String socketHost;
        public final int socketPort;
        public final String uri;
        public final String hostHeader;

        /**
         * @param hostHeader can be null, in which case socketHost &amp; socketPort is used
         */
        public Target(String socketHost, int socketPort, String uri, String hostHeader) {
            this.socketHost = socketHost;
            this.socketPort = socketPort;
            this.uri = uri;
            this.hostHeader = hostHeader;
        }

        public Target withSuffix(String suffix) {
            return new Target(socketHost, socketPort, uri + suffix, hostHeader);
        }
    }

    @SuppressWarnings("serial")
    public static class ProxyException extends VertxException {
        public final int statusCode;
        public final RejectReason reason;

        public ProxyException(int statusCode, Proxy.RejectReason reason, Throwable t) {
            super("Status: " + statusCode + ", reason: " + reason, t);
            this.statusCode = statusCode;
            this.reason = reason;
        }
    }

    public enum RejectReason {
        incomingRequestFail,
        outgoingRequestFail,
        incomingResponseFail,
        outgoingResponseFail,
        noHostHeader,
    }

    static final Set<String> hopByHopHeaders = new HashSet<>(asList(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "upgrade-insecure-requests",
            // TODO http2 hbh headers
            ":method",
            ":path",
            ":scheme",
            ":authority"
    ));

    static final Pattern connectionHeaderValueRE = Pattern.compile("\\s*,[\\s,]*+"); // from RFC2616

    static final String requestIdHeader = "X-Request-Id";

    static void copyEndToEndHeaders(MultiMap from, MultiMap to) {
        to.addAll(from);
        hopByHopHeaders.forEach(to::remove);

        String connectionHeader = from.get("connection");
        if (connectionHeader != null) {
            for (String name : connectionHeaderValueRE.split(connectionHeader.trim())) {
                to.remove(name);
            }
        }
    }

    static final AtomicLong requestId = new AtomicLong(Clock.systemUTC().millis());

    static class State {
        boolean clientFinished;
        boolean serverFinished;
    }

    public void handle(RoutingContext routingContext) {
        final HttpServerRequest sreq = routingContext.request();
        final boolean isTls = "https".equals(routingContext.request().scheme());
        final boolean isHTTP2 = routingContext.request().version() == HTTP_2;
        String reqId = sreq.headers().get(requestIdHeader);
        if (reqId == null) {
            reqId = Long.toString(requestId.getAndIncrement());
            sreq.headers().add(requestIdHeader, reqId);
        }
        final String chost = sreq.remoteAddress().host();
        final ProxyTracer tracer = tracerFactory.get();
        tracer.incomingRequestStart(routingContext, isTls, isHTTP2, chost);

        HttpServerResponse sres = sreq.response();
        sres.exceptionHandler(tracer::outgoingResponseException);
        sres.headersEndHandler(tracer::outgoingResponseHeadersEnd);
        sres.bodyEndHandler(tracer::outgoingResponseBodyEnd);
        if (!isHTTP2) {
            sres.headers().add("keep-alive", keepAliveHeaderValue);
            sres.headers().add("connection", "keep-alive");
        }
        sreq.exceptionHandler(t -> {
            tracer.incomingRequestException(t);
            routingContext.fail(new ProxyException(500, RejectReason.incomingRequestFail, t));
        });

        final State state = new State();

        targetResolver.resolveNextHop(routingContext, nextHop -> {
            if (nextHop == null) {
                NullPointerException e = new NullPointerException("nextHop must not be null");
                tracer.incomingRequestException(e);
                throw e;
            }
            tracer.nextHopResolved(nextHop);

            MultiMap sreqh = sreq.headers();
            String origHost = null;
            if (isHTTP2) {
                origHost = sreqh.get(":authority");
            }
            if (origHost == null) {
                origHost = sreqh.get("Host");
            }
            if (origHost == null) {
                ProxyException e = new ProxyException(400, RejectReason.noHostHeader, null);
                tracer.incomingRequestException(e);
                routingContext.fail(e);
                return;
            }
            boolean isWebsocket = !isHTTP2 && "websocket".equals(sreqh.get("upgrade"));
            if (isWebsocket) {
                MultiMap creqh = new CaseInsensitiveHeaders();
                propagateRequestHeaders(isTls, chost, sreqh, origHost, creqh);
                if (nextHop.hostHeader != null) {
                    creqh.set("Host", nextHop.hostHeader);
                } else {
                    creqh.remove("Host");
                }
                tracer.outgoingWebsocketInitial(creqh);
                client.websocket(nextHop.socketPort, nextHop.socketHost, nextHop.uri, creqh, cws -> {
                    // lol no headers copied
                    final boolean[] isClosed = {false};
                    ServerWebSocket sws = sreq.upgrade();
                    tracer.websocketEstablished();
                    for (final WebSocketBase[] pair : new WebSocketBase[][]{{sws, cws}, {cws, sws}}) {
                        pair[0].frameHandler(pair[1]::writeFrame)
                                .closeHandler(v -> {
                                    if (!isClosed[0]) {
                                        tracer.establishedWebsocketClosed();
                                        isClosed[0] = true;
                                        pair[1].close();
                                    }
                                })
                                .exceptionHandler(t -> {
                                    tracer.establishedWebsocketException(t);
                                    t.printStackTrace();
                                    if (!isClosed[0]) {
                                        isClosed[0] = true;
                                        try {
                                            pair[1].close();
                                        } catch (IllegalStateException e) {
                                            // whatever
                                        }
                                    }
                                });
                    }
                }, t -> {
                    tracer.outgoingWebsocketException(t);
                    t.printStackTrace();
                    sres.setStatusCode(HttpResponseStatus.BAD_GATEWAY.code());
                    if (t instanceof WebSocketHandshakeRejectedException) {
                        WebSocketHandshakeRejectedException e = (WebSocketHandshakeRejectedException) t;
                        sres.setStatusCode(e.resp.status().code());
                        sres.setStatusMessage(e.resp.status().reasonPhrase());
                        MultiMap cresh = new HeadersAdaptor(e.resp.headers());
                        copyEndToEndHeaders(cresh, sres.headers());
                        sres.headers().add("keep-alive", keepAliveHeaderValue);
                        sres.headers().add("connection", "keep-alive");
                        sres.headers().set("content-length", "0");
                    }
                    tracer.outgoingResponseInitial();
                    tracer.outgoingResponseHeadersEnd(null);
                    sres.end();
                    tracer.outgoingResponseBodyEnd(null);
                });
                return;
            }
            HttpClientRequest creq = client.request(sreq.method(), nextHop.socketPort, nextHop.socketHost, nextHop.uri);
            creq.setTimeout(SECONDS.toMillis(clientReceiveTimeout));
            creq.handler(cres -> {
                cres.exceptionHandler(t -> {
                    tracer.incomingResponseException(t);
                    if (!state.serverFinished) {
                        state.clientFinished = true;
                        state.serverFinished = true;
                        routingContext.fail(new ProxyException(502, RejectReason.incomingResponseFail, t));
                    }
                });
                tracer.incomingResponseStart(cres);
                sres.setStatusCode(cres.statusCode());
                sres.setStatusMessage(cres.statusMessage());
                MultiMap headers = cres.headers();
                copyEndToEndHeaders(headers, sres.headers());
                if (!isHTTP2) {
                    sres.headers().add("keep-alive", keepAliveHeaderValue);
                    sres.headers().add("connection", "keep-alive");
                }
                if (!headers.contains("content-length")) {
                    sres.setChunked(true);
                }
                tracer.outgoingResponseInitial();
                Pump resPump = Pump.pump(cres, sres);
                cres.endHandler(v -> {
                    tracer.incomingResponseEnd();
                    state.clientFinished = true;
                    if (!state.serverFinished) {
                        state.serverFinished = true;
                        sres.end();
                    }
                });
                resPump.start();
            });
            creq.exceptionHandler(t -> {
                tracer.outgoingRequestException(t);
                if (!state.serverFinished) {
                    state.clientFinished = true;
                    state.serverFinished = true;
                    routingContext.fail(new ProxyException(502, RejectReason.outgoingRequestFail, t));
                }
            });
            MultiMap creqh = creq.headers();
            propagateRequestHeaders(isTls, chost, sreqh, origHost, creqh);
            if (nextHop.hostHeader != null) {
                creq.setHost(nextHop.hostHeader);
            } else {
                creqh.remove("host");
            }
            if (sreqh.getAll("transfer-encoding").stream().anyMatch(v -> v.equals("chunked"))) {
                creq.setChunked(true);
            }
            sres.closeHandler(v -> {
                if (!state.clientFinished) {
                    state.clientFinished = true;
                    tracer.incomingConnectionPrematurelyClosed();
                    HttpConnection connection = creq.connection();
                    if (connection != null) {
                        connection.close();
                    } // else TODO
                }
                if (!state.serverFinished) {
                    state.serverFinished = true;
                    routingContext.fail(new ProxyException(0, RejectReason.outgoingResponseFail, null));
                }
            });
            tracer.outgoingRequestInitial(creq);
            if (sreq.isEnded()) {
                Buffer body = routingContext.getBody();
                if (body == null || body.length() == 0) {
                    creq.end();
                } else {
                    if (!creq.isChunked()) {
                        creq.putHeader("content-length", Integer.toString(body.length()));
                    }
                    creq.end(routingContext.getBody());
                }
                tracer.incomingRequestEnd();
            } else {
                sreq.endHandler(v -> {
                    try {
                        creq.end();
                    } catch (IllegalStateException ex) {
                        // ignore - nothing can be done - the request is already complete/closed - TODO log?
                    }
                    tracer.incomingRequestEnd();
                });
                Pump reqPump = Pump.pump(sreq, creq);
                reqPump.start();
            }
        });
    }

    private void propagateRequestHeaders(boolean isTls, String chost, MultiMap sreqh, String origHost, MultiMap creqh) {
        copyEndToEndHeaders(sreqh, creqh);
        creqh.set("X-Host", origHost);
        creqh.set("X-Forwarded-For", chost);
        creqh.set("X-Forwarded-Proto", isTls ? "https" : "http");
    }
}
