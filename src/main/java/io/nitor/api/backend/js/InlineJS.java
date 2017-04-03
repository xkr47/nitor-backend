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
package io.nitor.api.backend.js;

import io.vertx.core.Vertx;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static java.nio.charset.StandardCharsets.UTF_8;

public class InlineJS {
  private static final Logger logger = LogManager.getLogger(InlineJS.class);

  private static final String JVM_NPM = "vertx-js/util/jvm-npm.js";

  private final Vertx vertx;
  private final String scriptName;

  private static final ThreadLocal<ThreadState> threadState = new ThreadLocal<>();

  static class ThreadState {
    private ScriptEngine engine;
    private ScriptObjectMirror exports;
  }

  public InlineJS(Vertx vertx, String scriptName) {
    this.vertx = vertx;
    this.scriptName = scriptName;
  }

  public void call(String function, Object... args) {
    try {
      ThreadState state = getThreadState();
      if (functionExists(state, function)) {
        state.exports.callMember(function, args);
      }
    } catch (ScriptException e) {
      logger.warn("Script " + scriptName + "#" + function + " execution failed", e);
    }
  }

  private ThreadState getThreadState() throws ScriptException {
    ThreadState state = threadState.get();
    if (state == null) {
      state = init();
      threadState.set(state);
    }
    return state;
  }

  private boolean functionExists(ThreadState state, String functionName) {
    Object som = state.exports.getMember(functionName);
    return som != null && !som.toString().equals("undefined");
  }

  private ThreadState init() throws ScriptException {
    ThreadState state = new ThreadState();
    state.engine = new ScriptEngineManager().getEngineByName("nashorn");

    InputStream npmResource = getClass().getClassLoader().getResourceAsStream(JVM_NPM);
    state.engine.eval(new InputStreamReader(npmResource, UTF_8));

    state.engine.eval("require('vertx-js/future');");
    state.engine.put("__jvertx", vertx);
    String globs =
            "var Vertx = require('vertx-js/vertx'); var vertx = new Vertx(__jvertx);" +
                    "var console = require('vertx-js/util/console');" +
                    "var setTimeout = function(callback,delay) { return vertx.setTimer(delay, callback).doubleValue(); };" +
                    "var clearTimeout = function(id) { vertx.cancelTimer(id); };" +
                    "var setInterval = function(callback, delay) { return vertx.setPeriodic(delay, callback).doubleValue(); };" +
                    "var clearInterval = clearTimeout;" +
                    "var parent = this;" +
                    "var global = this;" +
                    "var process = {}; process.env=java.lang.System.getenv();";
    state.engine.eval(globs);

    state.exports = (ScriptObjectMirror) state.engine.eval("require.noCache('" + scriptName + "', null, true);");
    logger.info("Loaded script " + scriptName + ". Exports: " + state.exports.keySet());
    return state;
  }
}
