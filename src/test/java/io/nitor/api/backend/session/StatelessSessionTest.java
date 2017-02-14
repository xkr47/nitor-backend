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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static java.time.Clock.fixed;
import static java.time.Clock.systemUTC;
import static java.time.Instant.EPOCH;
import static java.time.Instant.now;
import static java.time.Instant.ofEpochSecond;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatelessSessionTest {
    @AfterEach
    public void resetClock() {
        StatelessSession.clock = systemUTC();
    }

    @Test
    void contextDataMatches() {
        StatelessSession s = new StatelessSession();
        s.setContextData("abba", 5, "cafe");
        assertTrue(s.contextDataMatches("abba", 5, "cafe"));
        assertFalse(s.contextDataMatches("abba", 6, "cafe"));
    }

    @Test
    void sourceIpSessionHandling() {
        StatelessSession.clock = fixed(EPOCH, ZoneId.of("UTC"));
        StatelessSession s = new StatelessSession();
        s.setSourceIpSession("1", ofEpochSecond(0));
        s.setSourceIpSession("2", ofEpochSecond(1));

        assertTrue(s.hasSourceIpSession("1"));
        assertTrue(s.hasSourceIpSession("2"));

        StatelessSession.clock = fixed(EPOCH.plusSeconds(1), ZoneId.of("UTC"));
        assertFalse(s.hasSourceIpSession("1"));
        assertTrue(s.hasSourceIpSession("2"));
    }

    @Test
    void equals() {
        StatelessSession s1 = new StatelessSession();
        StatelessSession s2 = new StatelessSession();
        assertTrue(s1.equals(s2));

        s1.setContextData(42);
        assertFalse(s1.equals(s2));
        s2.setContextData(42);
        assertTrue(s1.equals(s2));

        Instant now = now().plusSeconds(60);
        s1.setSourceIpSession("cafe", now);
        assertFalse(s1.equals(s2));
        s2.setSourceIpSession("cafe", now);
        assertTrue(s1.equals(s2));

        s1.sessionData.put("key", "value");
        assertFalse(s1.equals(s2));
        s2.sessionData.put("key", "value");
        assertTrue(s1.equals(s2));
    }

    @Test
    void cloning() {
        StatelessSession s1 = new StatelessSession();
        s1.setContextData(42);
        Instant now = now().plusSeconds(60);
        s1.setSourceIpSession("cafe", now);
        s1.sessionData.put("key", "value");

        StatelessSession s2 = new StatelessSession(s1);
        assertTrue(s1.equals(s2));
    }
}