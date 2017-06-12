/**
 * Copyright 2017 Jonas Berlin, Nitor Creations Oy
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

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;

public class SimpleLogProxyTracer implements ProxyTracer {

    static final Logger logger = LoggerFactory.getLogger(SimpleLogProxyTracer.class);
    protected RoutingContext ctx;
    protected String reqId;
    protected Proxy.Target nextHop;
    protected HttpClientRequest creq;
    protected HttpClientResponse cres;

    @Override
    public void incomingRequestStart(RoutingContext ctx, boolean isTls, boolean isHTTP2, String chost, String reqId) {
        this.ctx = ctx;
        this.reqId = reqId;
        String reqLogInfix = "Incoming " + (isHTTP2 ? "H2 " : "") + (isTls ? "HTTPS " : "") + "request from " + chost + ":";
        trace(LogType.sreq, reqLogInfix + dumpSReq(ctx.request(), ""), null);
    }

    @Override
    public void incomingRequestEnd() {
        trace(LogType.sreq, "Incoming request complete");
    }

    @Override
    public void incomingRequestException(Throwable t) {
        trace(LogType.sreq, "Incoming request fail", t);
    }

    @Override
    public void incomingConnectionPrematurelyClosed() {
        trace(LogType.sreq, "Incoming connection prematurely closed");
    }

    @Override
    public void nextHopResolved(Proxy.Target nextHop) {
        this.nextHop = nextHop;
    }

    @Override
    public void outgoingRequestInitial(HttpClientRequest creq) {
        this.creq = creq;
        trace(LogType.creq, "Outgoing request (initial) to " + nextHop.socketHost + ':' + nextHop.socketPort + ':' + dumpCReq(creq));
    }

    @Override
    public void outgoingRequestException(Throwable t) {
        trace(LogType.creq, "Outgoing request fail", t);
    }

    @Override
    public void incomingResponseStart(HttpClientResponse cres) {
        this.cres = cres;
        trace(LogType.cres, "Incoming response" + dumpCRes(cres));
    }

    @Override
    public void incomingResponseEnd() {
        trace(LogType.cres, "Incoming response complete");
    }

    @Override
    public void incomingResponseException(Throwable t) {
        trace(LogType.cres, "Incoming response fail", t);
    }

    @Override
    public void outgoingResponseInitial() {
        //trace(LogType.sres, "Outgoing response initial" + dumpSRes(ctx.response(), ""));
    }

    @Override
    public void outgoingResponseHeadersEnd(Void v) {
        trace(LogType.sres, "Outgoing response final" + dumpSRes(ctx.response(), /* logFile != null ? "" : */ ""));
    }

    @Override
    public void outgoingResponseBodyEnd(Void v) {
        trace(LogType.sres, "Outgoing response complete");
    }

    @Override
    public void outgoingResponseException(Throwable t) {
        trace(LogType.sres, "Outgoing response fail", t);
    }

    @Override
    public void outgoingWebsocketInitial(MultiMap creqh) {
        trace(LogType.creq, "Outgoing websocket request" + dumpCWebsocket(creqh));
    }

    @Override
    public void outgoingWebsocketException(Throwable t) {
        trace(LogType.sres, "Outgoing websocket fail", t);
    }

    @Override
    public void websocketEstablished() {
        trace(LogType.none, "Websocket established");
    }

    @Override
    public void establishedWebsocketClosed() {
        trace(LogType.none, "Websocket closed");
    }

    @Override
    public void establishedWebsocketException(Throwable t) {
        trace(LogType.none, "Websocket failed", t);
    }

    public enum LogType {
        sreq(">| "),
        creq(" |>"),
        cres(" |<"),
        sres("<| "),
        reqbody(">>>"),
        resbody("<<<"),
        none("   "),
        ;
        public final String graphic;
        LogType(String graphic) {
            this.graphic = graphic;
        }
    }

    void trace(LogType logType, String msg) {
        trace(logType, msg, null);
    }

    void trace(LogType logType, String msg, Throwable t) {
        logger.trace(logType.graphic + " [" + reqId + "] " + msg, t);
    }

    String dumpHeaders(MultiMap h, String indent) {
        StringBuilder sb = new StringBuilder();
        for (String name : h.names()) {
            sb.append("\n").append(indent).append(name).append(": ").append(h.getAll(name).stream().reduce((partial,element) -> partial + "\n" + indent + "  " + element).orElse(""));
        }
        return sb.toString();
    }

    String dumpCReq(HttpClientRequest req) {
        return "\n\t" + req.method().name() + " " + req.uri() + dumpHeaders(req.headers(), "\t");
    }
    String dumpSReq(HttpServerRequest req, String indent) {
        return "\n\t" + indent + req.method().name() + " " + req.uri() + " " + req.version().name() + dumpHeaders(req.headers(), "\t" + indent);
    }
    String dumpCRes(HttpClientResponse res) {
        return "\n\t" + res.statusCode() + " " + res.statusMessage() + dumpHeaders(res.headers(), "\t");
    }
    String dumpSRes(HttpServerResponse res, String indent) {
        return "\n\t" + indent + res.getStatusCode() + " " + res.getStatusMessage() + dumpHeaders(res.headers(), "\t" + indent);
    }
    String dumpCWebsocket(MultiMap creqh) {
        return "\n\t" + ctx.request().method().name() + " " + nextHop.uri + dumpHeaders(creqh, "\t");
    }
}
