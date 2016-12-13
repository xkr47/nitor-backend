package io.nitor.api.backend;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.Pump;
import io.vertx.ext.web.RoutingContext;

import java.time.Clock;
import java.util.HashSet;
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
    private final ErrorHandler errorHandler;

    public interface TargetResolver {
        Target resolveNextHop(HttpServerRequest request, boolean isTls);
    }

    public interface ErrorHandler {
        void fail(HttpServerRequest sreq, int status, RejectReason reason, String detail);
    }

    public Proxy(HttpClient client, TargetResolver targetResolver, ErrorHandler errorHandler) {
        this.client = client;
        this.targetResolver = targetResolver;
        this.errorHandler = errorHandler;
    }

    public static class Target {
        public String socketHost;
        public int socketPort;
        public String uri;
        public String hostHeader;

        public Target() {}

        public Target(String socketHost, int socketPort, String uri, String hostHeader) {
            this.socketHost = socketHost;
            this.socketPort = socketPort;
            this.uri = uri;
            this.hostHeader = hostHeader;
        }
    }

    public enum RejectReason {
        incomingRequestFail,
        outgoingRequestFail,
        incomingResponseFail,
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
        sreq.exceptionHandler(t -> errorHandler.fail(sreq, 500, RejectReason.incomingRequestFail, t.getMessage()));
        Target nextHop = targetResolver.resolveNextHop(sreq, isTls);

        if (nextHop == null) {
            // in this case the resolveNextHop takes care of sending the response
            return;
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
            errorHandler.fail(sreq, 400, RejectReason.noHostHeader, null);
            return;
        }
        HttpClientRequest creq = client.request(sreq.method(), nextHop.socketPort, nextHop.socketHost, nextHop.uri);
        creq.handler(cres -> {
            cres.exceptionHandler(t -> errorHandler.fail(sreq, 502, RejectReason.incomingResponseFail, t.getMessage()));

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
            cres.endHandler(v -> sres.end());
            resPump.start();
        });
        creq.exceptionHandler(t -> {
            errorHandler.fail(sreq, 502, RejectReason.outgoingRequestFail, t.getMessage());
        });
        MultiMap creqh = creq.headers();
        copyEndToEndHeaders(sreqh, creqh);
        creqh.set("Host", nextHop.hostHeader);
        creqh.set("X-Host", origHost);
        creqh.set("X-Forwarded-For", chost);
        creqh.set("X-Forwarded-Proto", isTls ? "https" : "http");
        if (!sreqh.contains("content-length")) {
            creq.setChunked(true);
        }
        Pump reqPump = Pump.pump(sreq, creq);
        sreq.endHandler(v -> creq.end());
        reqPump.start();
    }

}
