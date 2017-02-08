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

public class EagerHandlerWrapper<E> implements Handler<E> {
    private final Consumer<Handler<E>> handlerInstaller;
    private final BiConsumer<Handler<E>, E> wrapper;
    protected Handler<E> wrapped;
    protected boolean nullWrapped = true;
    protected boolean deactivated;

    public EagerHandlerWrapper(Consumer<Handler<E>> handlerInstaller, BiConsumer<Handler<E>, E> wrapper) {
        this.handlerInstaller = handlerInstaller;
        this.wrapper = wrapper;
        handlerInstaller.accept(this);
    }

    public <T> T handler(Handler<E> wrapped, T thiz) {
        if (deactivated) {
            handlerInstaller.accept(wrapped);
        } else if (nullWrapped = wrapped == null) {
            this.wrapped = (e) -> {
            };
        } else {
            this.wrapped = wrapped;
        }
        return thiz;
    }

    public void deactivate() {
        deactivated = true;
        handlerInstaller.accept(nullWrapped ? null : wrapped);
    }

    @Override
    public void handle(E event) {
        wrapper.accept(wrapped, event);
    }
}
