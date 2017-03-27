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
package io.nitor.api.backend.s3;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSSessionCredentials;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

import static io.netty.buffer.ByteBufUtil.hexDump;
import static io.nitor.api.backend.s3.AWSUrlEncoder.uriEncode;
import static io.vertx.core.http.HttpHeaders.AUTHORIZATION;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.time.Clock.systemUTC;

public class AWSRequestSigner {
    private static final DateTimeFormatter dateTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"));
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    private static final ThreadLocal<MessageDigest> SHA256_POOL;
    private static final ThreadLocal<Mac> HMACSHA256_POOL;

    static {
        SHA256_POOL = ThreadLocal.withInitial(() -> { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception ex) { throw new RuntimeException(ex);}});
        HMACSHA256_POOL = ThreadLocal.withInitial(() -> { try { return Mac.getInstance("HmacSHA256"); } catch (Exception ex) { throw new RuntimeException(ex);}});
    }

    private final String serviceHost;
    private final String region;
    private final String service;
    private final String signingRegion;
    private final AWSCredentialsProvider secretsProvider;
    private final Clock clock;

    private byte[] cachedSigningKey;
    private String cachedSigningKeyDate;

    public AWSRequestSigner(String region, String serviceHost, AWSCredentialsProvider secretsProvider) {
        this(region, serviceHost, secretsProvider, "s3", systemUTC());
    }

    public AWSRequestSigner(String region, String serviceHost, AWSCredentialsProvider secretsProvider, String service, Clock clock) {
        this.serviceHost = serviceHost;
        this.region = region;
        this.secretsProvider = secretsProvider;
        this.service = service;
        this.signingRegion = "/" + region + "/" + service + "/aws4_request";
        this.clock = clock;
    }

    private byte[] signingKey(Mac hmac, String date, AWSCredentials secrets) {
        synchronized (this) {
            if (date.equals(cachedSigningKeyDate)) {
                return cachedSigningKey;
            }
            cachedSigningKeyDate = date;

            byte[] secret = ("AWS4" + secrets.getAWSSecretKey()).getBytes(ISO_8859_1);
            byte[] dateKey = hmacSHA256(hmac, secret, date);
            byte[] dateRegionKey = hmacSHA256(hmac, dateKey, region);
            byte[] dateRegionService = hmacSHA256(hmac, dateRegionKey, service);
            cachedSigningKey = hmacSHA256(hmac, dateRegionService, "aws4_request");
            return cachedSigningKey;
        }
    }

    private byte[] hmacSHA256(Mac hmac, byte[] key, String data) {
        hmac.reset();
        try {
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hmac.doFinal(data.getBytes(ISO_8859_1));
        } catch (InvalidKeyException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void copyHeadersAndSign(HttpServerRequest sreq, HttpClientRequest creq, byte[] body) {
        MessageDigest sha256 = SHA256_POOL.get();
        String contentHash;
        if (body == null) {
            contentHash = UNSIGNED_PAYLOAD;
        } else {
            sha256.reset();
            contentHash = hexDump(sha256.digest(body));
        }
        MultiMap headers = creq.headers();

        String dateTime = dateTimeFormat.format(clock.instant());
        String date = dateTime.substring(0, 8);

        AWSCredentials secrets = secretsProvider.getCredentials();

        StringBuilder signedHeaders = new StringBuilder(64);
        StringBuilder sb = new StringBuilder(256);
        sb.append(creq.method().name()).append('\n');
        uriEncode(creq.path(), false, sb);
        sb.append("\n\n");
        putHeader(headers, sb, signedHeaders, "host", serviceHost);
        putHeader(headers, sb, signedHeaders, "if-modified-since", sreq);
        putHeader(headers, sb, signedHeaders, "if-none-match", sreq);
        putHeader(headers, sb, signedHeaders, "range", sreq);
        putHeader(headers, sb, signedHeaders, "x-amz-content-sha256", contentHash);
        putHeader(headers, sb, signedHeaders, "x-amz-date", dateTime);
        if (secrets instanceof AWSSessionCredentials) {
            putHeader(headers, sb, signedHeaders, "x-amz-security-token", ((AWSSessionCredentials) secrets).getSessionToken());
        }
        signedHeaders.setLength(signedHeaders.length() - 1);
        sb.append('\n').append(signedHeaders).append('\n');
        sb.append(contentHash);

        sha256.reset();
        String hashedCanonicalRequest = hexDump(sha256.digest(sb.toString().getBytes(ISO_8859_1)));
        sb.setLength(0);
        sb
                .append("AWS4-HMAC-SHA256\n")
                .append(dateTime).append('\n')
                .append(date).append(signingRegion).append('\n')
                .append(hashedCanonicalRequest);

        Mac hmac = HMACSHA256_POOL.get();
        String signature = hexDump(hmacSHA256(hmac, signingKey(hmac, date, secrets), sb.toString()));

        sb.setLength(0);
        sb.append("AWS4-HMAC-SHA256 ")
                .append("Credential=")
                .append(secrets.getAWSAccessKeyId())
                .append('/')
                .append(date)
                .append(signingRegion)
                .append(",SignedHeaders=")
                .append(signedHeaders)
                .append(",Signature=")
                .append(signature);

        headers.set(AUTHORIZATION, sb.toString());
    }

    private void putHeader(MultiMap headers, StringBuilder canonicalHeaders, StringBuilder signedHeaders, String name, String value) {
        headers.set(name, value);
        canonicalHeaders.append(name).append(':').append(value).append('\n');
        signedHeaders.append(name).append(';');
    }

    private void putHeader(MultiMap headers, StringBuilder canonicalHeaders, StringBuilder signedHeaders, String name, HttpServerRequest sreq) {
        String value = sreq.getHeader(name);
        if (value != null) {
            putHeader(headers, canonicalHeaders, signedHeaders, name, value.replaceAll("\\s+", " ").trim());
        }
    }
}
