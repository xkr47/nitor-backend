/**
 * Copyright 2017 Jonas Berlin
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.lang.System.getProperty;
import static java.lang.System.setProperty;

public abstract class VertxTest extends AbstractVerticle{
    protected Vertx vertx;
    protected Logger logger;

    private final CountDownLatch cdl = new CountDownLatch(1);
    private AsyncResult<Void> result;

    @BeforeEach
    public final void setupVertx() {
        System.out.println("------------------------------------------------------");
        if (getProperty("java.version", "").startsWith("9")) {
            setProperty("io.netty.noKeySetOptimization", "true");
        }
        setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
        setProperty("vertx.logger-delegate-factory-class-name", "io.vertx.core.logging.Log4j2LogDelegateFactory");
        logger = LoggerFactory.getLogger(getClass());
        vertx = Vertx.vertx();
        vertx.exceptionHandler(e -> logger.error("Fallback exception handler got", e));
        vertx.deployVerticle(this);
    }

    @Override
    public void start() throws Exception {
        runTest().setHandler(ar -> {
            logger.info("TEST complete");
            result = ar;
            cdl.countDown();
        });
    }

    @Test
    public final void test() throws Throwable {
        if (!cdl.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("Test timeout");
        }
        if (result.failed()) {
            throw result.cause();
        }
    }

    @AfterEach
    public final void teardownVertx() {
        vertx.close();
    }

    protected abstract Future<Void> runTest();
}
