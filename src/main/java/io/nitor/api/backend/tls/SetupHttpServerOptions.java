package io.nitor.api.backend.tls;

import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;

import java.util.List;

import static io.vertx.core.http.ClientAuth.REQUEST;
import static io.vertx.core.http.HttpVersion.HTTP_1_1;
import static io.vertx.core.http.HttpVersion.HTTP_2;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MINUTES;

public class SetupHttpServerOptions {
    // syntax is in JVM SSL format
    private static final List<String> cipherSuites = asList(
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"
    );

    public static HttpServerOptions createHttpServerOptions(JsonObject config) {
        JsonObject tls = config.getJsonObject("tls");
        HttpServerOptions httpOptions = new HttpServerOptions()
                // basic TCP/HTTP options
                .setReuseAddress(true)
                .setCompressionSupported(true)
                .setIdleTimeout((int) MINUTES.toSeconds(10))
                .setSsl(true)
                // server side certificate
                .setPemKeyCertOptions(new PemKeyCertOptions()
                        .setKeyPath(tls.getString("serverKey"))
                        .setCertPath(tls.getString("serverCert")))
                // TLS tuning
                .addEnabledSecureTransportProtocol("TLSv1.2")
                .addEnabledSecureTransportProtocol("TLSv1.3");
        if (tls.getBoolean("http2", true)) {
            httpOptions.setAlpnVersions(asList(HTTP_1_1, HTTP_2));
        } else {
            httpOptions.setAlpnVersions(asList(HTTP_1_1));
        }
        if (tls.getString("clientChain") != null) {
            // client side certificate
                httpOptions.setClientAuth(REQUEST)
                    .setTrustOptions(new PemTrustOptions()
                            .addCertPath(tls.getString("clientChain"))
                    );
        }
        if (config.getBoolean("useNativeOpenSsl")) {
            httpOptions
                    .setUseAlpn(true)
                    .setSslEngineOptions(new OpenSSLEngineOptions());
            cipherSuites.stream().map(SetupHttpServerOptions::javaCipherNameToOpenSSLName)
                    .forEach(httpOptions::addEnabledCipherSuite);
        } else {
            httpOptions
                    .setUseAlpn(DynamicAgent.enableJettyAlpn())
                    .setJdkSslEngineOptions(new JdkSSLEngineOptions());
            cipherSuites.forEach(httpOptions::addEnabledCipherSuite);
        }

        return httpOptions;
    }

    static String javaCipherNameToOpenSSLName(String name) {
        return name.replace("TLS_", "")
                .replace("WITH_AES_", "AES")
                .replace('_', '-');
    }
}
