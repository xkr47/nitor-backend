package io.nitor.api.backend.proxy;

import io.nitor.api.backend.proxy.Proxy.ProxyException;
import io.nitor.api.backend.proxy.Proxy.RejectReason;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import static io.vertx.core.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class SetupProxy {
    public static void setupProxy(Vertx vertx, Router router, JsonObject proxyConf) {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout((int) SECONDS.toMillis(10))
                .setIdleTimeout((int) MINUTES.toSeconds(2))
                .setMaxPoolSize(1000)
                .setMaxWaitQueueSize(20)
                .setProtocolVersion(HTTP_1_1)
                .setTryUseCompression(false));

        Proxy proxy = new Proxy(client,
                (routingContext, targetHandler) -> targetHandler.handle(new Proxy.Target(proxyConf.getString("host"), proxyConf.getInteger("port"), proxyConf.getString("path"), proxyConf.getString("hostHeader"))));

        router.get(proxyConf.getString("route")).handler(proxy::handle);

        router.get(proxyConf.getString("route")).handler(routingContext -> {
            if (routingContext.failed()) {
                ProxyException ex = (ProxyException) routingContext.failure();
                if (!routingContext.response().headWritten()) {
                    String statusMsg =
                            ex.getCause() != null ? ex.getCause().getMessage() :
                                    ex.reason == RejectReason.noHostHeader ? "Exhausted resources while trying to extract Host header from the request" : "";
                    routingContext.response().setStatusCode(ex.statusCode);
                    routingContext.response().headers().set("content-type", "text/plain;charset=UTF-8");
                    routingContext.response().end(statusMsg);
                }
            } else {
                routingContext.next();
            }
        });
    }
}
