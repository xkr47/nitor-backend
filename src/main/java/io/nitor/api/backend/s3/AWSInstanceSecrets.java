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

import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static io.nitor.api.backend.s3.AwsInstanceInfo.AWS_LOCAL_SERVER;
import static java.lang.System.currentTimeMillis;

public class AWSInstanceSecrets implements Supplier<AWSSecrets> {
    private final SyncHttp syncHttp;
    private final String iamToken;
    private AtomicLong expires = new AtomicLong();
    private AWSSecrets secrets;

    public AWSInstanceSecrets(SyncHttp syncHttp) {
        this.syncHttp = syncHttp;
        iamToken = syncHttp.getString(AWS_LOCAL_SERVER + "/latest/meta-data/iam/security-credentials/");
    }

    @Override
    public AWSSecrets get() {
        long now = currentTimeMillis();
        if (now < expires.get()) {
            return secrets;
        }
        synchronized (this) {
            JsonObject resp = syncHttp.getJson(AWS_LOCAL_SERVER + "/latest/meta-data/iam/security-credentials/" + iamToken);
            secrets = new AWSSecrets(resp.getString("AccessKeyId"), resp.getString("SecretAccessKey"), resp.getString("Token"));
            expires.set(Instant.parse(resp.getString("Expiration")).minusSeconds(60).toEpochMilli());
        }
        return secrets;
    }
}
