/**
 * Copyright 2016-2017 Nitor Creations Oy
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

import io.nitor.api.backend.proxy.Proxy.ProxyException;
import io.nitor.api.backend.proxy.Proxy.RejectReason;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import static io.vertx.core.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class SetupProxy {
    public static void setupProxy(Vertx vertx, Router router, JsonObject proxyConf) {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout((int) SECONDS.toMillis(10))
                .setIdleTimeout((int) MINUTES.toSeconds(2))
                .setMaxPoolSize(1000)
                .setMaxWaitQueueSize(20)
                .setProtocolVersion(HTTP_1_1)
                .setTryUseCompression(false));

        Proxy proxy = new Proxy(client,
                (routingContext, targetHandler) -> {
                    String prefix = proxyConf.getString("path");
                    if (prefix.endsWith("/")) {
                        prefix = prefix.substring(0, prefix.length() - 1);
                    }
                    String route = proxyConf.getString("route");
                    if (route.endsWith("*")) {
                        route = route.substring(0, route.length() - 1);
                    }
                    if (route.endsWith("/")) {
                        route = route.substring(0, route.length() - 1);
                    }
                    String suffix = routingContext.request().uri().substring(route.length());
                    targetHandler.handle(new Proxy.Target(proxyConf.getString("host"), proxyConf.getInteger("port"), prefix + suffix, proxyConf.getString("hostHeader")));
                });

        router.route(proxyConf.getString("route")).handler(proxy::handle);

        router.route(proxyConf.getString("route")).handler(routingContext -> {
            if (routingContext.failed()) {
                ProxyException ex = (ProxyException) routingContext.failure();
                if (!routingContext.response().headWritten()) {
                    String statusMsg =
                            ex.getCause() != null ? ex.getCause().getMessage() :
                                    ex.reason == RejectReason.noHostHeader ? "Exhausted resources while trying to extract Host header from the request" : "";
                    routingContext.response().setStatusCode(ex.statusCode);
                    routingContext.response().headers().set("content-type", "text/plain;charset=UTF-8");
                    routingContext.response().end(statusMsg);
                }
            } else {
                routingContext.next();
            }
        });
    }
}
