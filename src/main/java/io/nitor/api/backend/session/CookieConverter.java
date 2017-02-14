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

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.nitor.api.backend.session.ByteHelpers.read16;
import static io.nitor.api.backend.session.ByteHelpers.read32;
import static io.nitor.api.backend.session.ByteHelpers.write16;
import static io.nitor.api.backend.session.ByteHelpers.write32;
import static io.nitor.api.backend.session.Compressor.compress;
import static io.nitor.api.backend.session.Compressor.decompress;
import static io.nitor.api.backend.session.Encryptor.RANDOM;
import static io.vertx.ext.web.Cookie.cookie;
import static java.lang.System.arraycopy;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Base64.getUrlDecoder;
import static java.util.Base64.getUrlEncoder;

public class CookieConverter {
    private static final Base64.Encoder BASE64ENC = getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64DEC = getUrlDecoder();
    private static final int MAX_RANDOM_PADDING = 4;
    private static final int COOKIE_VERSION = 0;

    private static final ConcurrentHashMap<String, StatelessSession> cookieCache = new ConcurrentHashMap<>();
    private static final AtomicInteger cachePutCount = new AtomicInteger();

    private final String cookieName;
    private final Encryptor encryptor;
    private final int maxAge;
    private final int maxCacheSize;

    public CookieConverter(JsonObject sessionConf, int maxAge) {
        this.cookieName = sessionConf.getString("cookieName", "__Host-auth");
        this.encryptor = new Encryptor(sessionConf);
        this.maxAge = maxAge;
        this.maxCacheSize = sessionConf.getInteger("maxCookieCacheSize", 10_000);
    }

    public Cookie sessionToCookie(StatelessSession session) {
        byte[] randomPadding = generateRandomPaddingBytes();
        byte[] data = toBytes(session.sessionData);
        byte[] cookieBytes = new byte[randomPadding.length + 2 + 4 + data.length + session.sourceIpSessionExpirationTimes.size() * 8];

        if (cookieBytes.length + cookieName.length() + 42 > 4095) { // 42 = "; Secure; HttpOnly; MaxAge=123123; Path=/"
            throw new RuntimeException("Too large cookie");
        }

        int pos = 0;
        arraycopy(randomPadding, 0, cookieBytes, pos, randomPadding.length); pos += randomPadding.length;
        write16(cookieBytes, pos, data.length); pos += 2;
        write32(cookieBytes, pos, session.contextHash + COOKIE_VERSION); pos += 4;
        arraycopy(data, 0, cookieBytes, pos, data.length); pos += data.length;
        for (Map.Entry<Integer, Integer> entry : session.sourceIpSessionExpirationTimes.entrySet()) {
            write32(cookieBytes, pos, entry.getKey()); pos += 4;
            write32(cookieBytes, pos, entry.getValue()); pos += 4;
        }

        byte[] compressed = compress(cookieBytes);
        byte[] encrypted = encryptor.encrypt(compressed);
        String cookieValue = BASE64ENC.encodeToString(encrypted);
        return cookie(cookieName, cookieValue).setHttpOnly(true).setSecure(true).setMaxAge(maxAge).setPath("/");
    }

    private byte[] generateRandomPaddingBytes() {
        byte[] randomPadding = new byte[1 + RANDOM.nextInt(MAX_RANDOM_PADDING)];
        RANDOM.nextBytes(randomPadding);
        for (int i = 0; i < randomPadding.length - 1; ++i) {
            randomPadding[i] &= 0xFE;
        }
        randomPadding[randomPadding.length - 1] |= 1;
        return randomPadding;
    }

    private byte[] toBytes(Map<String, String> sessionData) {
        StringBuilder value = new StringBuilder(128);
        sessionData.forEach((k,v) -> value.append(k).append('=').append(v).append('|'));
        value.setLength(value.length() - 1);
        return value.toString().getBytes(UTF_8);
    }

    public StatelessSession cookieToSession(Cookie cookie) {
        String value = cookie.getValue();
        StatelessSession session = cookieCache.get(value);
        if (session != null) {
            return new StatelessSession(session);
        }

        session = new StatelessSession();

        byte[] encrypted = BASE64DEC.decode(value);
        byte[] decrypted = encryptor.decrypt(encrypted);
        byte[] cookieBytes = decompress(decrypted);

        int pos = skipRandomPadding(cookieBytes);
        int dataLength = read16(cookieBytes, pos); pos += 2;
        session.contextHash = read32(cookieBytes, pos) - COOKIE_VERSION; pos += 4;
        session.sessionData.putAll(parseSessionData(new String(cookieBytes, pos, dataLength, UTF_8))); pos += dataLength;
        while (pos < cookieBytes.length) {
            session.sourceIpSessionExpirationTimes.put(read32(cookieBytes, pos), read32(cookieBytes, pos + 4)); pos += 8;
        }

        cookieCache.put(value, new StatelessSession(session));
        if (cachePutCount.incrementAndGet() > maxCacheSize) {
            cachePutCount.set(0);
            cookieCache.clear();
        }

        return session;
    }

    private int skipRandomPadding(byte[] cookieBytes) {
        for (int i=0; i<=MAX_RANDOM_PADDING; ++i) {
            if ((cookieBytes[i] & 0x1) != 0) {
                return i + 1;
            }
        }
        throw new RuntimeException("invalid random bytes");
    }

    private HashMap<String, String> parseSessionData(String val) {
        HashMap<String, String> map = new HashMap<>();
        Stream.of(val.split("\\|")).map(s -> s.split("=")).forEach(s -> map.put(s[0], s[1]));
        return map;
    }

    public StatelessSession getSession(Iterable<Cookie> cookies) {
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookieToSession(cookie);
            }
        }
        return null;
    }
}