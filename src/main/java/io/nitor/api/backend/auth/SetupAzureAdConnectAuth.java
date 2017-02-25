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
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonArray;
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
import org.pac4j.vertx.handler.impl.CallbackHandlerOptions;
import org.pac4j.vertx.handler.impl.SecurityHandlerOptions;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT;
import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.WWW_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;
import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static io.nitor.api.backend.auth.SetupOpenIdConnectAuth.createOidcClient;
import static io.nitor.api.backend.auth.SetupOpenIdConnectAuth.createOidcConfig;
import static io.vertx.core.http.HttpMethod.GET;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;

public class SetupAzureAdConnectAuth {
    private static final Logger logger = LogManager.getLogger(SetupAzureAdConnectAuth.class);
    static final String AUTH_FAIL_PATH = "/auth-failed";
    static final String NOT_AUTHORIZED_PATH = "/not-authorized";

    public static void setupAzureAd(JsonObject adAuth, Router router, Vertx vertx, String publicURI, CookieSessionHandler sessionHandler) {
        OidcConfiguration oidcConfiguration = createOidcConfig(adAuth);
        OidcClient oidcClient = createOidcClient(oidcConfiguration);

        Pac4jAuthProvider authProvider = new Pac4jAuthProvider();

        final String callbackPath = "/oidc/callback";
        String path = adAuth.getString("path", "/*");
        router.route().handler(CookieHandler.create());
        router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)));
        router.route(callbackPath).handler(UserSessionHandler.create(authProvider));

        Clients clients = new Clients(publicURI + callbackPath, oidcClient);
        clients.setDefaultClient(oidcClient);

        Config config = new Config(clients);
        SecurityHandlerOptions options = new SecurityHandlerOptions().withClients(oidcClient.getName());

        CallbackHandlerOptions callbackOptions = new CallbackHandlerOptions();

        HttpClientOptions clientOptions = new HttpClientOptions();
        clientOptions.setConnectTimeout((int) SECONDS.toMillis(5));
        clientOptions.setIdleTimeout((int) SECONDS.toMillis(15));
        clientOptions.setSsl(true);
        HttpClient httpClient = vertx.createHttpClient(clientOptions);

        HashMap<String, String> headerMappings = new HashMap<>();
        adAuth.getJsonObject("headerMappings", new JsonObject())
            .forEach(mapping -> headerMappings.put(mapping.getKey(), mapping.getValue().toString()));

        HashMap<String, Pattern> requiredHeaderMatchers = new HashMap<>();
        adAuth.getJsonObject("requiredHeaders", new JsonObject())
                .forEach(mapping -> requiredHeaderMatchers.put(mapping.getKey(), Pattern.compile(mapping.getValue().toString())));

        CustomPac4jCallbackHandler callbackHandler = new CustomPac4jCallbackHandler(vertx, config, callbackOptions,
                e -> finalizeAuthentication(e, oidcConfiguration, adAuth, clients, httpClient, sessionHandler, headerMappings));

        router.get(callbackPath).handler(callbackHandler);

        router.get(NOT_AUTHORIZED_PATH).handler(ctx -> ctx.fail(FORBIDDEN.code()));
        router.get(AUTH_FAIL_PATH).handler(ctx -> ctx.fail(UNAUTHORIZED.code()));

        router.route(path).handler(new CustomPac4jSecurityHandler(vertx, config, authProvider, options, sessionHandler, headerMappings.keySet(), requiredHeaderMatchers));
    }

    private static void finalizeAuthentication(RoutingContext ctx, OidcConfiguration oidcConfiguration, JsonObject adAuth, Clients clients, HttpClient httpClient, CookieSessionHandler sessionHandler, HashMap<String, String> headerMappings) {
        String code = ctx.request().getParam("code");
        Buffer form;
        try {
            String graphScopes = Stream.of(oidcConfiguration.getScope().split(" ")).filter(s -> s.contains("graph.microsoft.com")).collect(joining("%20"));
            form = Buffer.buffer("code=" + code
                    + "&client_id=" + adAuth.getString("clientId")
                    + "&scope=" + graphScopes
                    + "&grant_type=authorization_code"
                    + "&client_secret=" + adAuth.getString("clientSecret")
                    + "&redirect_uri=" + URLEncoder.encode(clients.getCallbackUrl(), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        String tokenUrl = Stream.of(oidcConfiguration.getDiscoveryURI().split("/")).limit(4).collect(joining("/")) + "/oauth2/v2.0/token";
        httpClient.postAbs(tokenUrl)
                .putHeader(ACCEPT, APPLICATION_JSON)
                .putHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                .putHeader(CONTENT_LENGTH, String.valueOf(form.length()))
                .setTimeout(SECONDS.toMillis(10))
                .exceptionHandler(err -> {
                    logger.error("Failed to fetch graph access token", err);
                    ctx.reroute(GET, AUTH_FAIL_PATH);
                })
                .handler(resp -> processGraphTokenResponse(resp, ctx, httpClient, adAuth, sessionHandler, headerMappings))
                .end(form);
    }

    static void processGraphTokenResponse(HttpClientResponse resp, RoutingContext ctx, HttpClient httpClient, JsonObject azureAd, CookieSessionHandler sessionHandler, HashMap<String, String> headerMappings) {
        if (resp.statusCode() != OK.code()) {
            logger.warn("Failed to fetch graph access token: " + resp.statusMessage() + " - " + resp.getHeader(WWW_AUTHENTICATE));
            ctx.reroute(GET, AUTH_FAIL_PATH);
            return;
        }
        resp.bodyHandler(body -> {
            JsonObject json = body.toJsonObject();
            String token = json.getString("access_token");
            logger.debug("Got graph access response: {}", json);
            httpClient.getAbs(azureAd.getString("graphQueryURI", "https://graph.microsoft.com/beta/me?$expand=memberOf")) // NOTE v1.0 does not have working expand memberOf
                    .putHeader(AUTHORIZATION, "Bearer " + token)
                    .putHeader(ACCEPT, APPLICATION_JSON)
                    .setTimeout(SECONDS.toMillis(10))
                    .exceptionHandler(err -> {
                        logger.error("Failed to fetch user information", err);
                        ctx.reroute(GET, AUTH_FAIL_PATH);
                    })
                    .handler(r -> processMicrosoftUserInformation(r, ctx, sessionHandler, headerMappings))
                    .end();
        });
    }

    static void processMicrosoftUserInformation(HttpClientResponse resp, RoutingContext ctx, CookieSessionHandler sessionHandler, HashMap<String, String> headerMappings) {
        if (resp.statusCode() != OK.code()) {
            logger.warn("Failed to fetch user information: " + resp.statusMessage() + " - " + resp.getHeader(WWW_AUTHENTICATE));
            ctx.reroute(GET, AUTH_FAIL_PATH);
            return;
        }

        resp.bodyHandler(body2 -> {
            JsonObject userInfo = body2.toJsonObject();
            logger.debug("Got graph response: {}", userInfo);
            Map<String, String> sessionData = new HashMap<>();
            headerMappings.forEach((header, pointer) -> {
                        //MultiMap headers = ctx.request().headers();
                        //headers.remove(header); // for security reasons do not allow any header from request matching the mapped values through
                        ofNullable(customJsonPointerFetch(userInfo, pointer))
                                .ifPresent(val -> {
                                    //headers.add(header, val);
                                    sessionData.put(header, val);
                                });
                    });
            sessionHandler.setSessionData(ctx, sessionData);
            ctx.response().end();
        });
    }

    static String customJsonPointerFetch(JsonObject root, String pointer) {
        if (pointer.length() == 0) {
            return null;
        }
        int arrIdx = pointer.indexOf("[]");
        int dotIdx  = pointer.indexOf('.');
        if (dotIdx == 0) {
            pointer = pointer.substring(1);
            arrIdx = pointer.indexOf("[]");
            dotIdx  = pointer.indexOf('.');
        }
        if (arrIdx > 0 && arrIdx < dotIdx) {
            Object val = root.getValue(pointer.substring(0, arrIdx));
            if (val instanceof JsonArray) {
                StringBuffer sb = new StringBuffer();
                String remaining = pointer.substring(arrIdx + 2);
                ((JsonArray) val).forEach(o -> {
                    String value;
                    if (o instanceof JsonObject) {
                        value = customJsonPointerFetch((JsonObject) o, remaining);
                    } else {
                        value = o.toString();
                    }
                    if (value != null) {
                        sb.append(value).append(',');
                    }
                });
                if (sb.length() > 0) {
                    sb.setLength(sb.length() - 1);
                }
                return sb.toString();
            }
            return null;
        }
        String key = dotIdx < 0 ? pointer : pointer.substring(0, dotIdx);
        Object val = root.getValue(key);
        if (val instanceof JsonObject) {
            return customJsonPointerFetch((JsonObject) val, pointer.substring(dotIdx + 1));
        }
        if (val == null) {
            return null;
        }
        return val.toString();
    }
}
