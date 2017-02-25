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

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Cookie;
import org.junit.jupiter.api.Test;

import static java.time.Instant.now;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CookieConverterTest {
    final CookieConverter converter = new CookieConverter(new JsonObject().put("secretFile", "target/secret"), 100);

    @Test
    void sessionRoundtrip() {
        StatelessSession s = new StatelessSession();
        s.setContextData("Firefox", 42, "server");
        s.setSourceIpSession("127.0.0.2", now());
        s.sessionData.put("x-forwarded-user", "Teppo Testaaja");
        s.sessionData.put("x-forwarded-groups", "admin,user,tester");

        Cookie cookie = converter.sessionToCookie(s);
        assertEquals("/", cookie.getPath());
        assertNull(cookie.getDomain());

        StatelessSession roundtrip = converter.cookieToSession(cookie);
        assertEquals(s, roundtrip);

        // second invocation tests the cache
        roundtrip = converter.cookieToSession(cookie);
        assertEquals(s, roundtrip);
    }

}