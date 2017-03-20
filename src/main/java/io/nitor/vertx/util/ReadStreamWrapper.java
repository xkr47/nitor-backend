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
package io.nitor.vertx.util;

import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;

public class ReadStreamWrapper<T> implements ReadStream<T> {
    public final ReadStream<T> wrappedStream;

    public ReadStreamWrapper(ReadStream<T> wrappedStream) {
        this.wrappedStream = wrappedStream;
    }

    public ReadStream<T> exceptionHandler(Handler<Throwable> handler) {
        wrappedStream.exceptionHandler(handler);
        return this;
    }

    public ReadStream<T> handler(Handler<T> handler) {
        wrappedStream.handler(handler);
        return this;
    }

    public ReadStream<T> pause() {
        wrappedStream.pause();
        return this;
    }

    public ReadStream<T> resume() {
        wrappedStream.resume();
        return this;
    }

    public ReadStream<T> endHandler(Handler<Void> endHandler) {
        wrappedStream.endHandler(endHandler);
        return this;
    }
}
