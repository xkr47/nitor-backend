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
package io.nitor.api.backend.session;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.plaf.nimbus.State;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static io.netty.handler.codec.http.HttpHeaderNames.USER_AGENT;
import static java.time.Instant.now;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.regex.Pattern.compile;

public class CookieSessionHandler {
    private static final Logger logger = LogManager.getLogger(CookieSessionHandler.class);
    static final String CTX_KEY = "_stateless_session";

    private final CookieConverter cookieConverter;
    private static final Pattern NUMERIC = compile("[0-9]+");
    private final String serverName;
    private final Integer maxAge;

    public CookieSessionHandler(JsonObject sessionConf) {
        this(sessionConf, null);
    }

    CookieSessionHandler(JsonObject sessionConf, CookieConverter cookieConverter) {
        serverName = sessionConf.getString("serverName");
        maxAge = sessionConf.getInteger("sessionAge", (int) DAYS.toSeconds(14));
        this.cookieConverter = cookieConverter == null ? new CookieConverter(sessionConf, maxAge) : cookieConverter;
    }

    public Map<String, String> getSessionData(RoutingContext ctx) {
        return ofNullable(getSession(ctx)).filter(StatelessSession::isValid).map(s -> s.sessionData).orElse(null);
    }

    private StatelessSession getSession(RoutingContext ctx) {
        StatelessSession session = ctx.get(CTX_KEY);
        if (session != null) {
            return session;
        }

        HttpServerRequest request = ctx.request();
        String cleanUserAgent = cleanUserAgent(request);
        Object[] context = {serverName, cleanUserAgent};
        session = cookieConverter.getSession(ctx.cookies());
        if (session != null) {
            if (!session.contextDataMatches(context)) {
                logger.info("Invalid session context received from " + request.remoteAddress().host());
                session = null;
            }
        }
        if (session == null) {
            session = new StatelessSession();
            session.setContextData(context);
        } else if (session.hasSourceIpSession(request.remoteAddress().host())) {
            session.setValid(true);
        }
        ctx.put(CTX_KEY, session);
        return session;
    }

    private String cleanUserAgent(HttpServerRequest request) {
        String userAgent = request.getHeader(USER_AGENT);
        if (userAgent == null) {
            return null;
        }
        return NUMERIC.matcher(userAgent).replaceAll("0");
    }

    public void setSessionData(RoutingContext ctx, Map<String, String> parameters) {
        StatelessSession session = getSession(ctx);
        HttpServerRequest request = ctx.request();
        session.setValid(true);
        session.sessionData.clear();
        session.sessionData.putAll(parameters);
        session.setSourceIpSession(request.remoteAddress().host(), now().plusSeconds(maxAge));
        ctx.addCookie(cookieConverter.sessionToCookie(session));
    }
}
