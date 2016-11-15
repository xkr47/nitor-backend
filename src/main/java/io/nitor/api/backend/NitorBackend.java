package io.nitor.api.backend;

import io.nitor.api.backend.Proxy.RejectReason;
import io.nitor.api.backend.Proxy.Target;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Launcher;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.ext.web.Router;

import static com.nitorcreations.core.utils.KillProcess.killProcessUsingPort;
import static io.vertx.core.http.ClientAuth.REQUEST;
import static java.lang.Integer.getInteger;
import static java.lang.System.getProperty;
import static java.lang.System.setProperty;

public class NitorBackend extends AbstractVerticle
{
    private static final int listenPort = getInteger("port", 8443);

    public static void main(String... args)
    {
        killProcessUsingPort(listenPort);
        if (getProperty("java.version", "").startsWith("9")) {
            setProperty("io.netty.noKeySetOptimization", "true");
        }
        Launcher.main(new String[] { "run", NitorBackend.class.getName(), "-conf", "conf.json" });
    }

    @Override
    public void start()
    {
        Router router = Router.router(vertx);

        router.get("/healthCheck").handler(routingContext -> {
           routingContext.response().sendFile("pom.xml");
        });

        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout(10)
                .setIdleTimeout(120)
                .setMaxPoolSize(1000)
                .setMaxWaitQueueSize(20)
                .setTryUseCompression(false));
        Proxy proxy = new Proxy(client,
                (request, isTls) -> new Target("localhost", 8080, "/", "suchhost"),
                (request, status, reason, detail) -> {
            String statusMsg = detail != null ? detail : reason == RejectReason.noHostHeader ? "Exhausted resources while trying to extract Host header from the request" : "";
            request.response().setStatusCode(status);
            request.response().headers().set("content-type", "text/plain;charset=UTF-8");
            request.response().end(statusMsg);
        });
        router.get("/proxy").handler(routingContext -> proxy.handle(routingContext.request(), true));

        vertx.createHttpServer(
            new HttpServerOptions()
                // basic TCP/HTTP options
                .setReuseAddress(true)
                .setCompressionSupported(true)
                // TLS + HTTP/2
                .setSsl(true)
                .setUseAlpn(true)
                .setSslEngineOptions(new OpenSSLEngineOptions())
                // server side certificate
                .setPemKeyCertOptions(new PemKeyCertOptions()
                    .setKeyPath("certs/localhost.key.clear")
                    .setCertPath("certs/localhost.crt"))
                // TLS tuning
                .addEnabledSecureTransportProtocol("TLSv1.2")
                .addEnabledSecureTransportProtocol("TLSv1.3")
                .addEnabledCipherSuite("ECDHE-RSA-AES128-GCM-SHA256")
                .addEnabledCipherSuite("ECDHE-ECDSA-AES128-GCM-SHA256")
                .addEnabledCipherSuite("ECDHE-RSA-AES256-GCM-SHA384")
                .addEnabledCipherSuite("ECDHE-ECDSA-AES256-GCM-SHA384")
                // client side certificate
                .setClientAuth(REQUEST)
                .setTrustOptions(new PemTrustOptions()
                    .addCertPath("certs/client.chain")
                )
            )
            .requestHandler(router::accept)
            .listen(listenPort);
    }
}