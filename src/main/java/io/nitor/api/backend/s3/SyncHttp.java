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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SyncHttp {
    public SyncHttp() {
    }

    public JsonObject getJson(String url) {
        return new JsonObject(getString(url));
    }

    public String getString(String url) {
        Throwable err = null;
        for (int i=0; i<3; ++i) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(2000);
                connection.setDefaultUseCaches(false);
                connection.setRequestProperty("connection", "close");
                InputStream data = connection.getInputStream();

                StringBuilder sb = new StringBuilder(512);
                char[] buffer = new char[2048];
                try (Reader reader = new InputStreamReader(data, StandardCharsets.UTF_8)) {
                    while (true) {
                        int chars = reader.read(buffer);
                        if (chars < 0) {
                            break;
                        }
                        sb.append(buffer, 0, chars);
                    }
                }
                return sb.toString();
            } catch (IOException e) {
                err = e;
            }
        }
        throw new RuntimeException("Failed to fetch S3 data from " + url, err);
    }
}
