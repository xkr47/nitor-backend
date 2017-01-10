package io.vertx.core.http.impl;

import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;

public class WebSocketHandshakeRejectedException extends WebSocketHandshakeException {
    public final HttpResponse resp;

    public WebSocketHandshakeRejectedException(String s, HttpResponse resp) {
        super(s);
        this.resp = resp;
    }
}
