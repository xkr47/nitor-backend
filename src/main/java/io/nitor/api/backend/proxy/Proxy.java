package io.nitor.api.backend.proxy;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.VertxException;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.HeadersAdaptor;
import io.vertx.core.http.impl.WebSocketHandshakeRejectedException;
import io.vertx.core.streams.Pump;
import io.vertx.ext.web.RoutingContext;

import java.time.Clock;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static io.vertx.core.http.HttpVersion.HTTP_2;
import static java.util.Arrays.asList;

/**
 * Reverse HTTP proxy, ported to Java from https://github.com/xkr47/vhostproxy4/
 */
public class Proxy implements Handler<RoutingContext> {

    private final HttpClient client;
    private final TargetResolver targetResolver;

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

    public Proxy(HttpClient client, TargetResolver targetResolver) {
        this.client = client;
        this.targetResolver = targetResolver;
    }

    public static class Target {
        public String socketHost;
        public int socketPort;
        public String uri;
        public String hostHeader;

        public Target() {
        }

        /**
         * @param hostHeader can be null, in which case socketHost &amp; socketPort is used
         */
        public Target(String socketHost, int socketPort, String uri, String hostHeader) {
            this.socketHost = socketHost;
            this.socketPort = socketPort;
            this.uri = uri;
            this.hostHeader = hostHeader;
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
            // TODO http2 hbh headers
            ":method",
            ":path",
            ":scheme",
            ":authority"
    ));

    static final Pattern connectionHeaderValueRE = Pattern.compile("\\s*,[\\s,]*+"); // from RFC2616

    static final int serverIdleTimeout = 60;

    static final String keepAliveHeaderValue = "timeout=" + serverIdleTimeout;

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
        if (!sreq.headers().contains(requestIdHeader)) {
            String reqId = Long.toString(requestId.getAndIncrement());
            sreq.headers().add(requestIdHeader, reqId);
        }
        String chost = sreq.remoteAddress().host();

        HttpServerResponse sres = sreq.response();
        if (!isHTTP2) {
            sres.headers().add("keep-alive", keepAliveHeaderValue);
            sres.headers().add("connection", "keep-alive");
        }
        sreq.exceptionHandler(t -> routingContext.fail(new ProxyException(500, RejectReason.incomingRequestFail, t)));

        final State state = new State();

        targetResolver.resolveNextHop(routingContext, nextHop -> {
            if (nextHop == null) {
                throw new NullPointerException("nextHop must not be null");
            }

            MultiMap sreqh = sreq.headers();
            String origHost = null;
            if (isHTTP2) {
                origHost = sreqh.get(":authority");
            }
            if (origHost == null) {
                origHost = sreqh.get("Host");
            }
            if (origHost == null) {
                routingContext.fail(new ProxyException(400, RejectReason.noHostHeader, null));
                return;
            }
            boolean isWebsocket = !isHTTP2 && "websocket".equals(sreqh.get("upgrade"));
            if (isWebsocket) {
                MultiMap creqh = new CaseInsensitiveHeaders();
                propagateRequestHeaders(isTls, chost, sreqh, origHost, creqh);
                if (nextHop.hostHeader != null) {
                    creqh.set("Host", nextHop.hostHeader);
                }
                client.websocket(nextHop.socketPort, nextHop.socketHost, nextHop.uri, creqh, cws -> {
                    // lol no headers copied
                    final boolean[] isClosed = {false};
                    ServerWebSocket sws = sreq.upgrade();
                    sws.frameHandler(cws::writeFrame);
                    cws.frameHandler(sws::writeFrame);
                    sws.closeHandler(v -> {
                        if (!isClosed[0]) {
                            isClosed[0] = true;
                            cws.close();
                        }
                    });
                    cws.closeHandler(v -> {
                        if (!isClosed[0]) {
                            isClosed[0] = true;
                            sws.close();
                        }
                    });
                    sws.exceptionHandler(t -> {
                        t.printStackTrace();
                        try {
                            cws.close();
                        } catch (IllegalStateException e) {
                            // whatever
                        }
                    });
                    cws.exceptionHandler(t -> {
                        t.printStackTrace();
                        try {
                            sws.close();
                        } catch (IllegalStateException e) {
                            // whatever
                        }
                    });
                }, t -> {
                    t.printStackTrace();
                    sres.setStatusCode(HttpResponseStatus.BAD_GATEWAY.code());
                    if (t instanceof WebSocketHandshakeRejectedException) {
                        WebSocketHandshakeRejectedException e = (WebSocketHandshakeRejectedException) t;
                        sres.setStatusCode(e.resp.status().code());
                        sres.setStatusMessage(e.resp.status().reasonPhrase());
                        MultiMap headers = new HeadersAdaptor(e.resp.headers());
                        copyEndToEndHeaders(headers, sres.headers());
                        sres.headers().add("keep-alive", keepAliveHeaderValue);
                        sres.headers().add("connection", "keep-alive");
                        sres.headers().set("content-length", "0");
                    }
                    sres.end();
                });
                return;
            }
            HttpClientRequest creq = client.request(sreq.method(), nextHop.socketPort, nextHop.socketHost, nextHop.uri);
            creq.handler(cres -> {
                cres.exceptionHandler(t -> {
                    if (!state.serverFinished) {
                        state.clientFinished = true;
                        state.serverFinished = true;
                        routingContext.fail(new ProxyException(502, RejectReason.incomingResponseFail, t));
                    }
                });

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

                Pump resPump = Pump.pump(cres, sres);
                cres.endHandler(v -> {
                    state.clientFinished = true;
                    if (!state.serverFinished) {
                        state.serverFinished = true;
                        sres.end();
                    }
                });
                resPump.start();
            });
            creq.exceptionHandler(t -> {
                if (!state.serverFinished) {
                    state.clientFinished = true;
                    state.serverFinished = true;
                    routingContext.fail(new ProxyException(502, RejectReason.outgoingRequestFail, t));
                }
            });
            MultiMap creqh = creq.headers();
            creq.setHost(nextHop.hostHeader);
            propagateRequestHeaders(isTls, chost, sreqh, origHost, creqh);
            if (sreqh.getAll("transfer-encoding").stream().filter(v -> v.equals("chunked")).findFirst().isPresent()) {
                creq.setChunked(true);
            }
            Pump reqPump = Pump.pump(sreq, creq);
            sreq.endHandler(v -> creq.end());
            sres.closeHandler(v -> {
                if (!state.clientFinished) {
                    state.clientFinished = true;
                    creq.connection().close();
                }
                if (!state.serverFinished) {
                    state.serverFinished = true;
                    routingContext.fail(new ProxyException(0, RejectReason.outgoingResponseFail, null));
                }
            });
            reqPump.start();
        });
    }

    private void propagateRequestHeaders(boolean isTls, String chost, MultiMap sreqh, String origHost, MultiMap creqh) {
        copyEndToEndHeaders(sreqh, creqh);
        creqh.set("X-Host", origHost);
        creqh.set("X-Forwarded-For", chost);
        creqh.set("X-Forwarded-Proto", isTls ? "https" : "http");
    }
}
