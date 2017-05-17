/**
 * Copyright 2017 Nitor Creations Oy, Jonas Berlin
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

import io.nitor.api.backend.tls.SetupHttpServerOptions;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import static io.vertx.core.Future.future;

class ProxyTest extends VertxTest {
    public static final int PORT = 20111;

    protected Future<Void> runTest() {
        HttpServerOptions httpServerOptions = SetupHttpServerOptions.createHttpServerOptions(config());
        Router router = Router.router(vertx);
        JsonObject conf;
        SetupProxy.setupProxy(vertx, router, conf, httpServerOptions);
        vertx.createHttpServer(httpServerOptions)
                .requestHandler(router::accept)
                .listen(PORT, r -> {

                });

        Future<Void> done = future();
        vertx.setTimer(1000, l -> done.complete());
        return done;
    }
}
