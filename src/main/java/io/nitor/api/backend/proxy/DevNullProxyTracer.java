/**
 * Copyright 2017 Jonas Berlin
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
import io.vertx.ext.web.RoutingContext;

public class DevNullProxyTracer implements ProxyTracer {

    @Override
    public void incomingRequestStart(RoutingContext ctx, boolean isTls, boolean isHTTP2, String chost) {
    }

    @Override
    public void incomingRequestEnd() {
    }

    @Override
    public void incomingRequestException(Throwable t) {
    }

    @Override
    public void incomingConnectionPrematurelyClosed() {
    }

    @Override
    public void nextHopResolved(Proxy.Target nextHop) {
    }

    @Override
    public void outgoingRequestInitial(HttpClientRequest creq) {
    }

    @Override
    public void outgoingRequestException(Throwable t) {
    }

    @Override
    public void incomingResponseStart(HttpClientResponse cres) {
    }

    @Override
    public void incomingResponseEnd() {
    }

    @Override
    public void incomingResponseException(Throwable t) {
    }

    @Override
    public void outgoingResponseInitial() {
    }

    @Override
    public void outgoingResponseHeadersEnd(Void v) {
    }

    @Override
    public void outgoingResponseBodyEnd(Void v) {
    }

    @Override
    public void outgoingResponseException(Throwable t) {
    }

    @Override
    public void outgoingWebsocketInitial(MultiMap creqh) {
    }

    @Override
    public void outgoingWebsocketException(Throwable t) {
    }

    @Override
    public void websocketEstablished() {
    }

    @Override
    public void establishedWebsocketClosed() {
    }

    @Override
    public void establishedWebsocketException(Throwable t) {
    }
}
