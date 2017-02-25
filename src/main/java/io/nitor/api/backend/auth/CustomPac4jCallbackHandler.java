/**
 * Copyright 2017 Nitor Creations Oy
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
package io.nitor.api.backend.auth;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;
import org.pac4j.core.config.Config;
import org.pac4j.core.engine.CallbackLogic;
import org.pac4j.core.engine.DefaultCallbackLogic;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.http.HttpActionAdapter;
import org.pac4j.vertx.VertxProfileManager;
import org.pac4j.vertx.VertxWebContext;
import org.pac4j.vertx.handler.impl.CallbackHandlerOptions;
import org.pac4j.vertx.http.DefaultHttpActionAdapter;

public class CustomPac4jCallbackHandler implements Handler<RoutingContext> {

    protected static final Logger LOG = LoggerFactory.getLogger(CustomPac4jCallbackHandler.class);

    private final HttpActionAdapter httpActionHandler = new DefaultHttpActionAdapter();
    private final Vertx vertx;
    private final Config config;

    // Config elements which are all optional
    private final Boolean multiProfile;
    private final Boolean renewSession;
    private final String defaultUrl;
    private final Handler<RoutingContext> authFinishHandler;

    private final CallbackLogic<Void, VertxWebContext> callbackLogic = new DefaultCallbackLogic();
    {
        ((DefaultCallbackLogic<Void, VertxWebContext>) callbackLogic)
                .setProfileManagerFactory(VertxProfileManager::new);
    }

    public CustomPac4jCallbackHandler(final Vertx vertx,
                           final Config config,
                           final CallbackHandlerOptions options,
                           final Handler<RoutingContext> authFinishHandler) {
        this.vertx = vertx;
        this.config = config;
        this.multiProfile = options.getMultiProfile();
        this.renewSession = options.getRenewSession();
        this.defaultUrl = options.getDefaultUrl();
        this.authFinishHandler = authFinishHandler;
    }

    @Override
    public void handle(RoutingContext event) {

        // Can we complete the authentication process here?
        final VertxWebContext webContext = new VertxWebContext(event) {
            @Override
            public void completeResponse() {
                if (event.statusCode() == -1) {
                    authFinishHandler.handle(event);
                } else {
                    super.completeResponse();
                }
            }
        };

        vertx.executeBlocking(future -> {
                    callbackLogic.perform(webContext, config, httpActionHandler, defaultUrl, multiProfile, renewSession);
                    future.complete(null);
                },
                false,
                asyncResult -> {
                    // If we succeeded we're all good here, the job is done either through approving, or redirect, or
                    // forbidding
                    // However, if an error occurred we need to handle this here
                    if (asyncResult.failed()) {
                        event.fail(new TechnicalException(asyncResult.cause()));
                    }
                });

    }

}
