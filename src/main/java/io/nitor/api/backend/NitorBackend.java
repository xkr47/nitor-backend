package io.nitor.api.backend;

import io.nitor.api.backend.auth.SimpleConfigAuthProvider;
import io.nitor.api.backend.proxy.Proxy.ProxyException;
import io.nitor.api.backend.proxy.SetupProxy;
import io.nitor.api.backend.tls.SetupHttpServerOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.AuthHandler;
import io.vertx.ext.web.handler.BasicAuthHandler;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.util.Arrays;
import java.util.stream.Stream;

import static com.nitorcreations.core.utils.KillProcess.killProcessUsingPort;
import static java.lang.Integer.getInteger;
import static java.lang.System.*;

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
            PropertiesLauncher.main(Stream.concat(Stream.of("run", NitorBackend.class.getName()), Stream.of(args)).toArray(String[]::new));
        } catch (Exception ex) {
            ex.printStackTrace();
            exit(3);
        }
    }

    private final Logger logger = LoggerFactory.getLogger(NitorBackend.class);

    @Override
    public void start() {
        vertx.exceptionHandler(e -> {
           logger.error("Fallback exception handler got", e);
        });

        Router router = Router.router(vertx);

        router.route().handler(routingContext -> {
            routingContext.response().putHeader("strict-transport-security", "max-age=31536000; includeSubDomains");
            routingContext.next();
        });
        router.get("/healthCheck").handler(routingContext -> {
           routingContext.response().setStatusCode(200).end();
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

        JsonObject basicAuth = config().getJsonObject("basicAuth");
        if (basicAuth != null) {
            AuthHandler basicAuthHandler = BasicAuthHandler.create(new SimpleConfigAuthProvider(basicAuth.getJsonObject("users")), basicAuth.getString("realm", "nitor"));
            router.route(basicAuth.getString("path", "/*")).handler(basicAuthHandler);
        }

        JsonObject proxyConf = config().getJsonObject("proxy");
        if (proxyConf != null) {
            SetupProxy.setupProxy(vertx, router, proxyConf);
        }

        router.route().failureHandler(routingContext -> {
            String error = "ERROR";
            int statusCode = routingContext.statusCode();
            Throwable t = routingContext.failure();
            logger.info("Handling failure statusCode=" + statusCode, t);
            if (routingContext.response().headWritten()) {
                routingContext.response().end();
                routingContext.request().connection().close();
                return;
            }
            if (t != null) {
                if (t instanceof ProxyException) {
                    statusCode = ((ProxyException) t).statusCode;
                }
                error = "ERROR: " + t.toString();
            }
            routingContext.response().setStatusCode(statusCode != -1 ? statusCode : 500);
            routingContext.response().headers().set("Content-Type", "text/plain; charset=UTF-8");
            routingContext.response().headers().set("Content-Length", Integer.toString(error.length()));
            routingContext.response().end(error);
        });

        vertx.createHttpServer(SetupHttpServerOptions.createHttpServerOptions(config()))
                .requestHandler(router::accept)
                .listen(listenPort);
    }

}
