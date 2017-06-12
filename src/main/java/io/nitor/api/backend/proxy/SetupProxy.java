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
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static io.vertx.core.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.SECONDS;

public class SetupProxy {
    private static final Logger logger = LogManager.getLogger(SetupProxy.class);

    public static void setupProxy(Vertx vertx, Router router, JsonObject proxyConf, HttpServerOptions serverOptions) {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout((int) SECONDS.toMillis(proxyConf.getInteger("connectTimeout", 10)))
                .setIdleTimeout((int) SECONDS.toSeconds(proxyConf.getInteger("idleTimeout", 15)))
                .setMaxPoolSize(proxyConf.getInteger("maxPoolSize", 30))
                .setPipelining(proxyConf.getInteger("pipelineDepth", 0) > 1)
                .setPipeliningLimit(proxyConf.getInteger("pipelineDepth", 1))
                .setMaxWaitQueueSize(100)
                .setUsePooledBuffers(true)
                .setProtocolVersion(HTTP_1_1)
                .setTryUseCompression(false));

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
        final String proxyRoute = route;
        final Proxy.Target proxyTarget = new Proxy.Target(proxyConf.getString("host"), proxyConf.getInteger("port"), prefix, proxyConf.getString("hostHeader"));
        logger.info("Proxying {} to {}:{}/{}", route, proxyTarget.socketHost, proxyTarget.socketPort, proxyTarget.uri);

        Proxy proxy = new Proxy(client,
                (routingContext, targetHandler) -> {
                    String suffix = routingContext.request().uri().substring(proxyRoute.length());
                    targetHandler.handle(proxyTarget.withSuffix(suffix));
                },
                serverOptions.getIdleTimeout(),
                proxyConf.getInteger("clientReceiveTimeout", 300));

        router.route(proxyConf.getString("route")).handler(proxy::handle);

        router.route(proxyConf.getString("route")).failureHandler(routingContext -> {
            if (routingContext.failed()) {
                ProxyException ex = (ProxyException) routingContext.failure();
                if (!routingContext.response().headWritten()) {
                    String statusMsg = "";
                    int statusCode = 502;
                    if (ex != null) {
                        statusCode = ex.statusCode;
                        if (ex.getCause() != null) {
                            statusMsg = ex.getCause().getMessage();
                        } else if (ex.reason == RejectReason.noHostHeader) {
                            statusMsg = "Exhausted resources while trying to extract Host header from the request";
                        }
                    }
                    routingContext.response().setStatusCode(statusCode);
                    routingContext.response().headers().set("content-type", "text/plain;charset=UTF-8");
                    routingContext.response().end(statusMsg);
                }
            } else {
                routingContext.next();
            }
        });
    }
}
