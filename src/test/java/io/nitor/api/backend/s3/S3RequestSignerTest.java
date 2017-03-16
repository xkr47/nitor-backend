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

import com.example.mockito.MockitoExtension;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extensions;
import org.mockito.Mock;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static io.vertx.core.http.HttpHeaders.AUTHORIZATION;
import static io.vertx.core.http.HttpMethod.GET;
import static java.time.Clock.fixed;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Extensions(@ExtendWith(MockitoExtension.class))
class S3RequestSignerTest {
    @Test
    void copyHeadersAndSign(@Mock HttpServerRequest sreq, @Mock HttpClientRequest creq, @Mock MultiMap outgoingHeaders) {
        ZoneId utc = ZoneId.of("UTC");
        AWSRequestSigner s = new AWSRequestSigner("us-east-1", "examplebucket.s3.amazonaws.com",
                () -> new AWSSecrets("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", null),
                "s3",
                fixed(ZonedDateTime.of(2013,5, 24, 0, 0, 0, 0, utc).toInstant(), utc));

        when(creq.method()).thenReturn(GET);
        when(creq.path()).thenReturn("/test.txt");
        when(creq.headers()).thenReturn(outgoingHeaders);
        when(sreq.getHeader("range")).thenReturn("bytes=0-9");

        s.copyHeadersAndSign(sreq, creq, new byte[0]);

        verify(outgoingHeaders).set(AUTHORIZATION, "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

}