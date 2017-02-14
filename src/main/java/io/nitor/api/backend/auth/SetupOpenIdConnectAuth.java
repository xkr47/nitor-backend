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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.UserSessionHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.oidc.client.OidcClient;
import org.pac4j.oidc.config.OidcConfiguration;
import org.pac4j.vertx.auth.Pac4jAuthProvider;
import org.pac4j.vertx.handler.impl.CallbackHandler;
import org.pac4j.vertx.handler.impl.CallbackHandlerOptions;
import org.pac4j.vertx.handler.impl.SecurityHandler;
import org.pac4j.vertx.handler.impl.SecurityHandlerOptions;

public class SetupOpenIdConnectAuth {

    static OidcConfiguration createOidcConfig(JsonObject oidcAuth) {
        OidcConfiguration oidcConfiguration = new OidcConfiguration();
        oidcConfiguration.setClientId(oidcAuth.getString("clientId"));
        oidcConfiguration.setSecret(oidcAuth.getString("clientSecret"));
        oidcConfiguration.setDiscoveryURI(oidcAuth.getString("configurationURI"));
        oidcConfiguration.setUseNonce(true);
        oidcConfiguration.setScope(oidcAuth.getString("scope", "openid email profile"));
        oidcConfiguration.setClientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        oidcConfiguration.setPreferredJwsAlgorithm(JWSAlgorithm.RS256);
        oidcConfiguration.setResponseType("code");
        oidcAuth.getJsonObject("customParam", new JsonObject()).forEach(e -> oidcConfiguration.addCustomParam(e.getKey(), e.getValue().toString()));
        return oidcConfiguration;
    }

    public static void setupOpenIdConnect(JsonObject oidcAuth, Router router, Vertx vertx, String publicURI) {
        OidcConfiguration oidcConfiguration = createOidcConfig(oidcAuth);
        OidcClient oidcClient = createOidcClient(oidcConfiguration);

        Pac4jAuthProvider authProvider = new Pac4jAuthProvider();

        final String callbackPath = "/oidc/callback";
        String path = oidcAuth.getString("path", "/*");
        router.route().handler(CookieHandler.create());
        router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)));
        router.route().handler(UserSessionHandler.create(authProvider));

        Clients clients = new Clients(publicURI + callbackPath, oidcClient);
        clients.setDefaultClient(oidcClient);

        Config config = new Config(clients);
        SecurityHandlerOptions options = new SecurityHandlerOptions().withClients(oidcClient.getName());

        CallbackHandlerOptions callbackOptions = new CallbackHandlerOptions();
        Handler<RoutingContext> callbackHandler = new CallbackHandler(vertx, config, callbackOptions);

        router.get(callbackPath).handler(callbackHandler);

        router.route(path).handler(new SecurityHandler(vertx, config, authProvider, options));
    }

    static OidcClient createOidcClient(OidcConfiguration oidcConfiguration) {
        OidcClient oidcClient = new OidcClient(oidcConfiguration);
        oidcClient.setName("NitorAuth");
        oidcClient.setIncludeClientNameInCallbackUrl(false);
        return oidcClient;
    }
}
