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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import static java.lang.Integer.toHexString;
import static java.time.Clock.systemUTC;
import static java.time.Instant.ofEpochSecond;
import static java.util.Objects.hash;

public class StatelessSession {
    private static final long EPOC_TO_YEAR_2020 = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toEpochSecond();
    static Clock clock = systemUTC();

    private boolean valid;
    int contextHash;
    final Map<Integer, Integer> sourceIpSessionExpirationTimes = new HashMap<>();
    final Map<String, String> sessionData = new HashMap<>();

    public StatelessSession() {

    }

    public StatelessSession(StatelessSession session) {
        contextHash = session.contextHash;
        valid = session.valid;
        sourceIpSessionExpirationTimes.putAll(session.sourceIpSessionExpirationTimes);
        sessionData.putAll(session.sessionData);
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public boolean isValid() {
        return valid;
    }

    public void setContextData(Object ... keys) {
        contextHash = hash(keys);
    }

    public boolean contextDataMatches(Object ... keys) {
        return contextHash == hash(keys);
    }

    public void setSourceIpSession(String ipAddress, Instant expirationTime) {
        sourceIpSessionExpirationTimes.put(ipAddress.hashCode(), moveEpocTo2020(expirationTime));
    }

    public boolean hasSourceIpSession(String ipAddress) {
        int now = moveEpocTo2020(clock.instant());
        sourceIpSessionExpirationTimes.values().removeIf(expireationTime -> expireationTime < now);
        return sourceIpSessionExpirationTimes.containsKey(ipAddress.hashCode());
    }

    private static int moveEpocTo2020(Instant time) {
        return (int) (time.getEpochSecond() - EPOC_TO_YEAR_2020);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof StatelessSession)) {
            return false;
        }
        StatelessSession other = (StatelessSession) obj;
        return contextHash == other.contextHash
                && sourceIpSessionExpirationTimes.equals(other.sourceIpSessionExpirationTimes)
                && sessionData.equals(other.sessionData);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("[valid=").append(valid)
                .append("][contextHash=").append(toHexString(contextHash))
                .append("][sourceIpSessionExpirationTimes={");
        sourceIpSessionExpirationTimes.forEach((k,v) -> sb.append(toHexString(k)).append('=').append(ofEpochSecond(v + EPOC_TO_YEAR_2020)).append(", "));
        if (!sourceIpSessionExpirationTimes.isEmpty()) {
            sb.setLength(sb.length() - 2);
        }
        sb.append("}][sessionData=").append(sessionData).append(']');
        return sb.toString();
    }
}
