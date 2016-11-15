package io.nitor.api.backend;

import static com.nitorcreations.core.utils.KillProcess.killProcessUsingPort;
import static io.vertx.core.http.ClientAuth.REQUEST;
import static io.vertx.core.http.ClientAuth.REQUIRED;
import static java.lang.Integer.getInteger;
import static java.lang.System.getProperty;
import static java.lang.System.setProperty;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Launcher;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.TCPSSLOptions;
import io.vertx.ext.web.Router;

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

        vertx.createHttpServer(
            new HttpServerOptions()
                .setSsl(true)
                .setUseAlpn(true)
                .setClientAuth(REQUEST)
                .setReuseAddress(true)
                .setSslEngineOptions(new OpenSSLEngineOptions())
                /*.setPemTrustOptions(new PemTrustOptions()
                    .addCertPath("certs/localhost.chain"))*/
                .setPemKeyCertOptions(new PemKeyCertOptions()
                    .setKeyPath("certs/localhost.key.clear")
                    .setCertPath("certs/localhost.crt"))
                /*.setTrustStoreOptions(
                    new JksOptions().
                        setPath("/path/to/your/truststore.jks").
                        setPassword("password-of-your-truststore")
                )*/
                )
            .requestHandler(router::accept)
            .listen(listenPort);
    }
}