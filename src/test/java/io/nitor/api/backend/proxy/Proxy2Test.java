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

/*
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.vertx.core.Future.future;
*/
class Proxy2Test  {
/*
    Vertx vertx;
    final CountDownLatch cdl = new CountDownLatch(1);
    AsyncResult<Void> result;

    @BeforeEach
    public void setup() {
        vertx = Vertx.vertx();
        vertx.deployVerticle(this);
    }

    @Override
    public void start() throws Exception {
        runTest().setHandler(ar -> {
            result = ar;
            cdl.countDown();
        });
    }

    @Test
    public void test() throws Throwable {
        cdl.await(10, TimeUnit.SECONDS);
        if (result.failed()) {
            throw result.cause();
        }
    }

    @AfterEach
    public void teardown() {
        vertx.close();
    }

    private Future<Void> runTest() {
        Future<Void> done = future();
        vertx.setTimer(1000, l -> done.complete());
        return done;
    }
*/}
