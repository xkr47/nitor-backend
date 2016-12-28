package io.nitor.api.backend.auth;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.AbstractUser;
import io.vertx.ext.auth.AuthProvider;
import io.vertx.ext.auth.User;

import java.util.HashMap;
import java.util.Map;

public class SimpleConfigAuthProvider implements AuthProvider {
    private final Map<String, String> users = new HashMap<>();

    public SimpleConfigAuthProvider(JsonObject basicAuth) {
        basicAuth.forEach(e -> users.put(e.getKey(), e.getValue().toString()));
    }

    @Override
    public void authenticate(JsonObject authInfo, Handler<AsyncResult<User>> resultHandler) {
        ImmediateAsyncResult<User> result;
        String username = authInfo.getString("username");
        if (users.getOrDefault(username, "").equals(authInfo.getString("password"))) {
            result = new ImmediateAsyncResult<>(new SimpleUser(username));
        } else {
            result = new ImmediateAsyncResult<>(new RuntimeException("Auth failed"));
        }
        resultHandler.handle(result);
    }

    public static class SimpleUser extends AbstractUser {
        private static final AsyncResult<Boolean> trueAsyncResult = new ImmediateAsyncResult<>(true);
        private final String username;
        private JsonObject principal;

        public SimpleUser(String username) {
            this.username = username;
        }

        @Override
        protected void doIsPermitted(String permission, Handler<AsyncResult<Boolean>> resultHandler) {
            resultHandler.handle(trueAsyncResult);
        }

        @Override
        public JsonObject principal() {
            if (principal == null) {
                principal = new JsonObject().put("username", username);
            }
            return principal;
        }

        @Override
        public void setAuthProvider(AuthProvider authProvider) {

        }
    }

    public static class ImmediateAsyncResult<T> implements AsyncResult<T> {
        private final T result;
        private final Throwable failure;

        public ImmediateAsyncResult(T result) {
            this.result = result;
            this.failure = null;
        }

        public ImmediateAsyncResult(Throwable failure) {
            this.result = null;
            this.failure = failure;
        }

        @Override
        public T result() {
            return result;
        }

        @Override
        public Throwable cause() {
            return failure;
        }

        @Override
        public boolean succeeded() {
            return failure == null;
        }

        @Override
        public boolean failed() {
            return failure != null;
        }
    }
}
