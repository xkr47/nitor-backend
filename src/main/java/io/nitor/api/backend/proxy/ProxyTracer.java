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
import io.vertx.ext.web.RoutingContext;

public interface ProxyTracer {

    void incomingRequestStart(RoutingContext ctx, boolean isTls, boolean isHTTP2, String chost, String reqId);

    void incomingRequestEnd();

    void incomingRequestException(Throwable t);

    void incomingConnectionPrematurelyClosed();

    void nextHopResolved(Proxy.Target nextHop);

    void outgoingRequestInitial(HttpClientRequest creq);

    void outgoingRequestException(Throwable t);

    void incomingResponseStart(HttpClientResponse cres);

    void incomingResponseEnd();

    void incomingResponseException(Throwable t);

    void outgoingResponseInitial();

    void outgoingResponseHeadersEnd(Void v);

    void outgoingResponseBodyEnd(Void v);

    void outgoingResponseException(Throwable throwable);

    void outgoingWebsocketInitial(MultiMap creqh);

    void outgoingWebsocketException(Throwable t);

    void websocketEstablished();

    void establishedWebsocketClosed();

    void establishedWebsocketException(Throwable t);
}
