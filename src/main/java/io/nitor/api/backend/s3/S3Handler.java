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

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.Pump;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.vertx.core.http.HttpVersion.HTTP_1_1;
import static java.lang.System.getenv;
import static java.util.concurrent.TimeUnit.SECONDS;

public class S3Handler implements Handler<RoutingContext> {
    private static final Logger logger = LogManager.getLogger(S3Handler.class);

    private final String s3Host;
    private final String basePath;
    private final HttpClient http;
    private final AWSRequestSigner signer;
    private final int routeLength;

    public S3Handler(Vertx vertx, JsonObject conf, int routeLength) {
        this.routeLength = routeLength;
        SyncHttp syncHttp = new SyncHttp();

        String region = conf.getString("region", getenv("AWS_DEFAULT_REGION"));
        if (region == null) {
            region = new AwsInstanceInfo(syncHttp).getRegion();
            logger.info("Using s3 region " + region);
        }
        this.s3Host = ("us-east-1".equals(region) ? "s3" : "s3-" + region) + ".amazonaws.com";

        String bucket = conf.getString("bucket");
        basePath = '/' + bucket + '/' + conf.getString("basePath", "");

        AWSSecrets confSecrets = new AWSSecrets(conf.getString("accessKey"), conf.getString("secretKey"), null);
        Supplier<AWSSecrets> secretSupplier;
        if (confSecrets.isValid()) {
            secretSupplier = () -> confSecrets;
        } else {
            AWSSecrets envSecrets = new AWSSecrets(getenv("AWS_ACCESS_KEY_ID"), getenv("AWS_SECRET_ACCESS_KEY"), getenv("AWS_SESSION_TOKEN"));
            if (envSecrets.isValid()) {
                secretSupplier = () -> envSecrets;
            } else {
                logger.info("Using instance profile to fetch secrets");
                secretSupplier = new AWSInstanceSecrets(syncHttp);
            }
        }

        signer = new AWSRequestSigner(region, s3Host, secretSupplier);

        http = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout((int) SECONDS.toMillis(conf.getInteger("connectTimeout", 5)))
                .setIdleTimeout((int) SECONDS.toSeconds(conf.getInteger("idleTimeout", 60)))
                .setMaxPoolSize(conf.getInteger("maxPoolSize", 100))
                .setPipelining(false)
                .setMaxWaitQueueSize(100)
                .setUsePooledBuffers(true)
                .setProtocolVersion(HTTP_1_1)
                .setMaxRedirects(5)
                .setTryUseCompression(false));
    }

    @Override
    public void handle(RoutingContext ctx) {
        HttpServerRequest sreq = ctx.request();
        String path = sreq.path();
        if (path.contains("../")) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        path = path.substring(routeLength);
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        path = basePath + path;

        switch (sreq.method()) {
            case GET:
                HttpClientRequest creq = http.get(s3Host, path);
                prepareRequest(sreq, creq);
                HttpServerResponse sres = ctx.response();
                sres.closeHandler(close -> creq.connection().close());
                creq.handler(cres -> mapResponse(cres, sres));
                creq.end();
                break;
            default:
                ctx.response().setStatusCode(BAD_REQUEST.code()).end();
        }
    }

    private void prepareRequest(HttpServerRequest sreq, HttpClientRequest creq) {
        signer.copyHeadersAndSign(sreq, creq, null);
    }

    private void mapResponse(HttpClientResponse cres, HttpServerResponse sres) {
        cres.exceptionHandler(t -> {
            logger.error("Error processing s3 request", t);
            if (!sres.ended()) {
                sres.setStatusCode(502);
                sres.end();
            }
        });

        sres.setStatusCode(cres.statusCode());
        sres.setStatusMessage(cres.statusMessage());
        if (cres.statusCode() != 200) {
            sres.end();
            return;
        }

        MultiMap headers = sres.headers();
        cres.headers().forEach(entry -> {
            String key = entry.getKey();
            if (key.startsWith("x-amz-")) {
                return;
            }
            String lKey = key.toLowerCase();
            if ("server".equals(lKey) ||
                    "accept-ranges".equals(lKey) ||
                    "transfer-encoding".equals(lKey) ||
                    "date".equals(lKey) ||
                    "connection".equals(lKey)) {
                return;
            }
            headers.add(key, entry.getValue());
        });
        // TODO handle http 1.0 that requires connection header

        Pump resPump = Pump.pump(cres, sres);
        cres.endHandler(v -> sres.end());
        resPump.start();
    }
}