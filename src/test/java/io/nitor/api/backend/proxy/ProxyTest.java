/**
 * Copyright 2017 Nitor Creations Oy, Jonas Berlin
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
package io.nitor.api.backend.proxy;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.junit.jupiter.api.*;

import java.nio.file.Paths;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.vertx.core.Future.future;
import static java.lang.System.setProperty;
import static java.nio.file.Files.exists;

class ProxyTest extends AbstractVerticle {

    static {
        /*
        if (exists(Paths.get("log4j2.xml"))) {
            setProperty("log4j.configurationFile", "log4j2.xml");
        }
        */
        setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
        setProperty("vertx.logger-delegate-factory-class-name", "io.vertx.core.logging.Log4j2LogDelegateFactory");
    }

    Vertx vertx;
    final Exchanger<AsyncResult<Void>> resultExch = new Exchanger<>();
    protected final Logger logger = LoggerFactory.getLogger(ProxyTest.class);

    @BeforeEach
    public void setup() {
        vertx = Vertx.vertx();
        vertx.deployVerticle(this);
    }

    @Override
    public void start() throws Exception {
        runTest().setHandler(ar -> {
            try {
                resultExch.exchange(ar);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    @Test
    public void test() throws Throwable {
        logger.info("test");
        AsyncResult<Void> result = resultExch.exchange(null, 10, TimeUnit.SECONDS);
        logger.info("/test");
        if (result.failed()) {
            throw result.cause();
        }
    }

    @AfterEach
    public void teardown() throws Throwable {
        logger.info("teardown");
        final Exchanger<AsyncResult<Void>> teardownExch = new Exchanger<>();
        vertx.close(ar -> {
            try {
                teardownExch.exchange(ar);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        AsyncResult<Void> result = teardownExch.exchange(null, 10, TimeUnit.SECONDS);
        logger.info("/teardown");
        if (result.failed()) {
            throw result.cause();
        }
    }

    private Future<Void> runTest() {
        Future<Void> done = future();
        vertx.setTimer(1000, l -> done.complete());
        return done;
    }
}
