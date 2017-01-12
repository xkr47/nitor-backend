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
package io.nitor.api.backend;

import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;

import static java.lang.System.currentTimeMillis;

public class AccessLogHandler {
    public void handle(RoutingContext routingContext) {
        new AccessLogEntry().init(routingContext);
        routingContext.next();
    }

    public static class AccessLogEntry {
        public final long requestStartTime = currentTimeMillis();
        //public long requestBodyEndTime;
        //public long responseStartTime;
        public long responseBodyEndTime;
        public String reqLine;

        private RoutingContext routingContext;

        final Logger logger = LogManager.getLogger(AccessLogEntry.class);

        public void init(RoutingContext routingContext) {
            this.routingContext = routingContext;
            this.reqLine = routingContext.request().method() + " " + routingContext.request().uri() + " " + routingContext.request().version();
            routingContext.data().put("accessLog", this);
            routingContext.addBodyEndHandler(v -> {
                responseBodyEndTime = currentTimeMillis();
                logger.info(toLogEntry());
            });
        }

        private String toLogEntry() {
            String cert = "";
            try {
                X509Certificate[] certs = routingContext.request().peerCertificateChain();
                for (X509Certificate c : certs) {
                    if (!cert.isEmpty()) {
                        cert += " ::: ";
                    }
                    cert += c.getSubjectDN().getName();
                }
            } catch (SSLPeerUnverifiedException e) {
                // ignore
            }
            return '"' + reqLine + "\" \"" + routingContext.response().getStatusCode() + ' ' + routingContext.response().getStatusMessage() + "\" "
                    + (responseBodyEndTime - requestStartTime) + "ms "
                    + routingContext.response().bytesWritten() + " \""
                    + nte(routingContext.request().getHeader("Referrer")) + "\" \""
                    + nte(routingContext.request().getHeader("User-Agent")) + "\" \""
                    + cert + '"';
        }

        private String nte(String header) {
            return header == null ? "" : header;
        }
    }
}