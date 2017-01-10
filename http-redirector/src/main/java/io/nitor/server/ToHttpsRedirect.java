/**
 * Copyright 2016-2017 Nitor Creations Oy
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
package io.nitor.server;

import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.stream.Stream;

import static java.lang.System.err;
import static java.lang.System.exit;
import static java.net.InetAddress.getLocalHost;
import static java.net.StandardSocketOptions.SO_LINGER;
import static java.net.StandardSocketOptions.TCP_NODELAY;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.Locale.US;

public class ToHttpsRedirect {
    public static void main(String... args) throws Exception {
        String defaultHost = Stream.of(args).findFirst().orElse(getLocalHost().getCanonicalHostName());
        Channel inherited = SelectorProvider.provider().inheritedChannel();
        if (inherited == null || !(inherited instanceof ServerSocketChannel)) {
            err.println("Requires listen socket to be passed using inted protocol");
            exit(2);
        }
        ServerSocketChannel listenSocket = (ServerSocketChannel) inherited;
        listenSocket.configureBlocking(true);

        ByteBuffer reqBuf = ByteBuffer.allocate(16384);
        ByteBuffer respBuf = ByteBuffer.allocate(16384);
        byte[] baseResp = ("HTTP/1.0 301 Moved Permanently\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "Location: https://"
        ).getBytes(ISO_8859_1);
        respBuf.put(baseResp);
        while (true) {
            try (SocketChannel socket = listenSocket.accept()){
                socket.configureBlocking(true);
                socket.setOption(TCP_NODELAY, true);
                socket.setOption(SO_LINGER, 5);
                socket.finishConnect();
                reqBuf.clear();
                socket.read(reqBuf);
                // FIXME: handle partial requests
                String input = new String(reqBuf.array(), ISO_8859_1);
                String lines[] = input.split("\n");
                String path = lines[0].split(" ")[1];
                String host = Stream.of(lines)
                        .skip(1)
                        .filter(l -> l.length() > 5)
                        .filter(l -> l.substring(0, 5).toLowerCase(US).equals("host:"))
                        .map(l -> l.split(":")[1])
                        .map(String::trim)
                        .findFirst().orElse(defaultHost);
                String redirect = host + path + "\r\n\r\n";
                respBuf.clear();
                respBuf.position(baseResp.length);
                respBuf.put(redirect.getBytes(ISO_8859_1));
                respBuf.flip();
                socket.write(respBuf);
            } catch (Exception ex) {
                err.printf("Error processing request", ex);
            }
        }
    }
}
