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

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class LazyHandlerWrapper<E> implements Handler<E> {
    private final Consumer<Handler<E>> handlerInstaller;
    private final BiConsumer<Handler<E>, E> wrapper;
    protected Handler<E> wrapped;

    public LazyHandlerWrapper(Consumer<Handler<E>> handlerInstaller, BiConsumer<Handler<E>, E> wrapper) {
        this.handlerInstaller = handlerInstaller;
        this.wrapper = wrapper;
    }

    public <T> T handler(Handler<E> wrapped, T thiz) {
        if (wrapped == null) {
            handlerInstaller.accept(null);
            this.wrapped = (e) -> {
            };
        } else {
            this.wrapped = wrapped;
            handlerInstaller.accept(this);
        }
        return thiz;
    }

    @Override
    public void handle(E event) {
        wrapper.accept(wrapped, event);
    }
}
