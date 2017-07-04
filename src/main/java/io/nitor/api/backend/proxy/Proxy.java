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
import io.nitor.vertx.util.LazyHandlerWrapper;
import io.nitor.vertx.util.ReadStreamWrapper;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.VertxException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.HeadersAdaptor;
import io.vertx.core.http.impl.WebSocketHandshakeRejectedException;
import io.vertx.core.streams.Pump;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.RoutingContext;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static io.vertx.core.http.HttpVersion.HTTP_2;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Reverse HTTP proxy, ported to Java from https://github.com/xkr47/vhostproxy4/
 */
public class Proxy implements Handler<RoutingContext> {
    private static final Logger log = LogManager.getLogger(Proxy.class);

    private final HttpClient client;
    private final TargetResolver targetResolver;
    private final String keepAliveHeaderValue;
    private final int clientReceiveTimeout;
    private final Supplier<ProxyTracer> tracerFactory;
    private final PumpStarter pump;

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

    @FunctionalInterface
    public interface PumpStarter {
        enum Type {REQUEST, RESPONSE}
        void start(Type type, ReadStream<Buffer> rs, WriteStream<Buffer> ws, ProxyTracer t);
    }

    public static class DefaultPumpStarter implements PumpStarter {
        @Override
        public void start(Type type, ReadStream<Buffer> rs, WriteStream<Buffer> ws, ProxyTracer t) {
            Pump.pump(rs, ws).start();
        }
    }

    /**
     * @param tracerFactory used to create ProxyTracer instances for each processed request. ProxyTracer instances receive all the lifecycle events related to proxying of a single request.
     * @param pump used to pump data. Typically <tt>new DefaultPumpStarter()</tt>
     */
    public Proxy(HttpClient client, TargetResolver targetResolver, int serverIdleTimeout, int clientReceiveTimeout, Supplier<ProxyTracer> tracerFactory, PumpStarter pump) {
        this.client = client;
        this.targetResolver = targetResolver;
        this.keepAliveHeaderValue = "timeout=" + (serverIdleTimeout - 5);
        this.clientReceiveTimeout = clientReceiveTimeout;
        this.tracerFactory = tracerFactory;
        this.pump = pump;
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
        boolean expecting100;
        boolean receivedRequestBodyBefore100;
        boolean requestComplete;
    }

    public void handle(RoutingContext routingContext) {
        final HttpServerRequest sreq = routingContext.request();
        final boolean isTls = "https".equals(routingContext.request().scheme());
        final boolean isHTTP2 = routingContext.request().version() == HTTP_2;
        final String chost = sreq.remoteAddress().host();
        final ProxyTracer tracer = tracerFactory.get();
        String reqId = sreq.headers().get(requestIdHeader);
        boolean hadRequestId = reqId != null;
        if (reqId == null) {
            reqId = Long.toString(requestId.getAndIncrement());
        }
        tracer.incomingRequestStart(routingContext, isTls, isHTTP2, chost, reqId);
        if (!hadRequestId) {
            sreq.headers().add(requestIdHeader, reqId);
        }

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
            String expectStr;
            state.expecting100 = null != (expectStr = sreq.headers().get("expect")) && expectStr.equalsIgnoreCase("100-continue");
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
                final boolean reqCompletedBeforeResponse = state.requestComplete;
                if (state.expecting100) {
                    log.info("Got " + cres.statusCode() + " instead of 100 Continue");
                    if (!isHTTP2) {
                        if (/* state.receivedRequestBodyBefore100 && */ !reqCompletedBeforeResponse) {
                            // TODO investigate whether vertx is able to handle the server request correctly without us closing the conn
                            // but actually the client might have data in transit..
                            log.info("Client might have started streaming data anyway, so request message boundary is lost. Continue streaming, but close server connection after response complete.");
                            sres.headers().set("connection", "close");
                        } else {
                            log.info("Client had streamed the complete data anyway. Can carry on without closing server conn.");
                        }
                    }
                }
                if (!isHTTP2) {
                    if (!sres.headers().contains("connection") || !sres.headers().get("connection").contains("close")) {
                        sres.headers().add("keep-alive", keepAliveHeaderValue);
                        sres.headers().add("connection", "keep-alive");
                    }
                }
                if (!headers.contains("content-length")) {
                    sres.setChunked(true);
                }
                tracer.outgoingResponseInitial();
                cres.endHandler(v -> {
                    tracer.incomingResponseEnd();
                    state.clientFinished = true;
                    if (!state.serverFinished) {
                        state.serverFinished = true;
                        sres.end();
                    }
                    if (state.expecting100 && /* state.receivedRequestBodyBefore100 && */ !reqCompletedBeforeResponse) {
                        log.info("Client had started streaming data anyway, so request message boundary is lost. Close client connection.");
                        creq.connection().close();
                    }
                });
                pump.start(PumpStarter.Type.RESPONSE, cres, sres, tracer);
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
                state.requestComplete = true;
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
                    state.requestComplete = true;
                    try {
                        creq.end();
                    } catch (IllegalStateException ex) {
                        // ignore - nothing can be done - the request is already complete/closed - TODO log?
                    }
                    tracer.incomingRequestEnd();
                });

                ReadStream<Buffer> sreqStream;
                if (state.expecting100) {
                    log.info("Expect: 100");
                    creq.continueHandler(v -> {
                        // no longer expecting 100, it's like a normal not-expecting-100 request from now on
                        state.expecting100 = false;
                        // since we received 100 Continue, we know the server agrees to accept all the request body, so we can assume we are forgiven for sending data early
                        state.receivedRequestBodyBefore100 = false;
                        log.info("Got 100, propagating");
                        sres.writeContinue();
                    });
                    // in this case we must flush request headers before the body is sent
                    creq.sendHead();
                    sreqStream = new ReadStreamWrapper<Buffer>(sreq) {
                        final LazyHandlerWrapper<Buffer> handlerWrapper = new LazyHandlerWrapper<Buffer>(super::handler, null) {
                            @Override
                            public void handle(Buffer event) {
                                log.info("Got first request body chunk");
                                if (state.expecting100) {
                                    log.info("Got request body before '100 Continue'");
                                    // data received despite not having yet recived 100-continue
                                    state.receivedRequestBodyBefore100 = true;
                                }
                                deactivate();
                                wrapped.handle(event);
                            }
                        };

                        @Override
                        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
                            return handlerWrapper.handler(handler, this);
                        }
                    };
                } else {
                    log.info("Not expect-100");
                    sreqStream = sreq;
                }
                pump.start(PumpStarter.Type.REQUEST, sreqStream, creq, tracer);
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
