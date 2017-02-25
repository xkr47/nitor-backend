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

import io.nitor.api.backend.session.CookieSessionHandler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;
import org.pac4j.core.config.Config;
import org.pac4j.vertx.auth.Pac4jAuthProvider;
import org.pac4j.vertx.handler.impl.SecurityHandler;
import org.pac4j.vertx.handler.impl.SecurityHandlerOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static io.nitor.api.backend.auth.SetupAzureAdConnectAuth.NOT_AUTHORIZED_PATH;
import static io.vertx.core.http.HttpMethod.GET;
import static java.util.Optional.ofNullable;

public class CustomPac4jSecurityHandler extends SecurityHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CustomPac4jSecurityHandler.class);
    private final CookieSessionHandler sessionHandler;
    private final Set<String> forbiddenHeaders;
    private final HashMap<String, Pattern> requiredHeaderMatchers;

    public CustomPac4jSecurityHandler(Vertx vertx, Config config, Pac4jAuthProvider authProvider,
                                      SecurityHandlerOptions options, CookieSessionHandler sessionHandler,
                                      Set<String> forbiddenHeaders, HashMap<String, Pattern> requiredHeaderMatchers) {
        super(vertx, config, authProvider, options);
        this.sessionHandler = sessionHandler;
        this.forbiddenHeaders = forbiddenHeaders;
        this.requiredHeaderMatchers = requiredHeaderMatchers;
    }

    @Override
    public void handle(RoutingContext routingContext) {
        Optional<Map<String, String>> headers = ofNullable(sessionHandler.getSessionData(routingContext));
        if (headers.isPresent()) {
            MultiMap h = routingContext.request().headers();
            forbiddenHeaders.forEach(h::remove);
            h.addAll(headers.get());

            if (!requiredHeaderMatchers.entrySet().stream()
                .allMatch(e -> headerMatches(h.get(e.getKey()), e.getValue()))) {
                LOG.info("Not authorised to view resource " + routingContext.request().path());

                routingContext.reroute(GET, NOT_AUTHORIZED_PATH);
                return;
            }

            LOG.info("Cookie authorised to view resource " + routingContext.request().path());

            // cleanup unwanted session cookie
            routingContext.removeCookie("vertx-web.session");

            routingContext.next();
            return;
        }

        super.handle(routingContext);
    }

    static boolean headerMatches(String header, Pattern pattern) {
        return header != null && pattern.matcher(header).matches();
    }
}
