package io.nitor.api.backend;

import static com.nitorcreations.core.utils.KillProcess.killProcessUsingPort;
import static io.vertx.core.http.ClientAuth.REQUEST;
import static java.lang.Boolean.getBoolean;
import static java.lang.Integer.getInteger;
import static java.lang.System.getProperty;
import static java.lang.System.setProperty;
import static java.util.Arrays.asList;

import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLPeerUnverifiedException;

import io.nitor.api.backend.Proxy.RejectReason;
import io.nitor.api.backend.Proxy.Target;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Launcher;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.ext.web.Router;

public class NitorBackend extends AbstractVerticle
{
    private static final int listenPort = getInteger("port", 8443);

    // syntax is in JVM SSL format
    private static final List<String> cipherSuites = asList(
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"
    );

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

        boolean useNativeOpenSsl = getBoolean("openssl");

        HttpServerOptions httpOptions = new HttpServerOptions()
            // basic TCP/HTTP options
            .setReuseAddress(true)
            .setCompressionSupported(true)
            // TLS + HTTP/2
            .setSsl(true)
            // server side certificate
            .setPemKeyCertOptions(new PemKeyCertOptions()
                .setKeyPath("certs/localhost.key.clear")
                .setCertPath("certs/localhost.crt"))
            // TLS tuning
            .addEnabledSecureTransportProtocol("TLSv1.2")
            .addEnabledSecureTransportProtocol("TLSv1.3")
            // client side certificate
            .setClientAuth(REQUEST)
            .setTrustOptions(new PemTrustOptions()
                .addCertPath("certs/client.chain")
            );
        if (useNativeOpenSsl) {
            httpOptions
                .setUseAlpn(true)
                .setSslEngineOptions(new OpenSSLEngineOptions());
            cipherSuites.stream().map(NitorBackend::javaCipherNameToOpenSSLName)
              .forEach(httpOptions::addEnabledCipherSuite);
        } else {
            httpOptions
                .setUseAlpn(DynamicAgent.enableJettyAlpn())
                .setJdkSslEngineOptions(new JdkSSLEngineOptions());
            cipherSuites.forEach(httpOptions::addEnabledCipherSuite);
        }

        vertx.createHttpServer(httpOptions)
            .requestHandler(router::accept)
            .listen(listenPort);
    }

    static String javaCipherNameToOpenSSLName(String name) {
        return name.replace("TLS_", "")
            .replace("WITH_AES_", "AES")
            .replace('_', '-');
    }
}