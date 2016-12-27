package io.nitor.api.backend;

import io.nitor.api.backend.Proxy.ProxyException;
import io.nitor.api.backend.Proxy.RejectReason;
import io.nitor.api.backend.Proxy.Target;
import io.nitor.api.backend.tls.SetupHttpServerOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Launcher;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.ext.web.Router;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.util.Arrays;

import static com.nitorcreations.core.utils.KillProcess.killProcessUsingPort;
import static io.vertx.core.http.HttpVersion.HTTP_1_1;
import static java.lang.Boolean.getBoolean;
import static java.lang.Integer.getInteger;
import static java.lang.System.exit;
import static java.lang.System.getProperty;
import static java.lang.System.setProperty;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class NitorBackend extends AbstractVerticle
{
    private static final int listenPort = getInteger("port", 8443);

    public static void main(String... args) throws Exception {
        setProperty("java.nio.channels.spi.SelectorProvider", InheritedChannelSelectorProvider.class.getName());
        if (!InheritedChannelSelectorProvider.hasInheritedChannel()) {
            killProcessUsingPort(listenPort);
        }
        if (getProperty("java.version", "").startsWith("9")) {
            setProperty("io.netty.noKeySetOptimization", "true");
        }
        try {
            Launcher.main(new String[]{"run", NitorBackend.class.getName(), "-conf", "conf.json"});
        } catch (Exception ex) {
            ex.printStackTrace();
            exit(3);
        }
    }

    @Override
    public void start() {
        Router router = Router.router(vertx);

        router.route().handler(routingContext -> {
            routingContext.response().putHeader("strict-transport-security", "max-age=31536000; includeSubDomains");
            routingContext.next();
        });
        router.get("/healthCheck").handler(routingContext -> {
           routingContext.response().sendFile("pom.xml");
        });
        router.get("/certCheck").handler(routingContext -> {
            String resp;
            try {
                resp = "Certs: " + Arrays.toString(routingContext.request().peerCertificateChain());
            } catch (SSLPeerUnverifiedException e) {
                resp = "No client certs available:" + e.getMessage();
            }
            routingContext.response().setChunked(true).write(resp).end();
        });

        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout((int) SECONDS.toMillis(10))
                .setIdleTimeout((int) MINUTES.toSeconds(2))
                .setMaxPoolSize(1000)
                .setMaxWaitQueueSize(20)
                .setProtocolVersion(HTTP_1_1)
                .setTryUseCompression(false));
        Proxy proxy = new Proxy(client,
                (routingContext, targetHandler) -> targetHandler.handle(new Target("example.org", 80, "/", "example.org")));
        router.get("/proxy").handler(proxy::handle);
        router.get("/proxy").handler(routingContext -> {
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

        boolean useNativeOpenSsl = getBoolean("openssl");

        vertx.createHttpServer(SetupHttpServerOptions.createHttpServerOptions(useNativeOpenSsl))
                .requestHandler(router::accept)
                .listen(listenPort);
    }

}
