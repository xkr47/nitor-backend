package io.nitor.api.backend;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import sun.nio.ch.SelChImpl;
import sun.nio.ch.SelectionKeyImpl;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.ProtocolFamily;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.channels.*;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;

import static java.lang.System.err;

public class InheritedChannelSelectorProvider extends SelectorProvider {
    private static final SelectorProvider orig;
    private static final Channel inherited;

    static {
        orig = sun.nio.ch.DefaultSelectorProvider.create();
        err.println("Got original selector provider: " + orig);
        try {
            inherited = orig.inheritedChannel();
            err.println("Got inherited channel: " + inherited);

        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch inherited channel", e);
        }
    }

    public static boolean hasInheritedChannel() {
        return inherited != null;
    }

    @Override
    public DatagramChannel openDatagramChannel() throws IOException {
        if (inherited != null && inherited instanceof DatagramChannel) {
            err.println("Returning inherited channel: " + inherited);
            return (DatagramChannel) inherited;
        }
        return orig.openDatagramChannel();
    }

    @Override
    public DatagramChannel openDatagramChannel(ProtocolFamily family) throws IOException {
        return orig.openDatagramChannel(family);
    }

    @Override
    public Pipe openPipe() throws IOException {
        return orig.openPipe();
    }

    @Override
    public AbstractSelector openSelector() throws IOException {
        return orig.openSelector();
    }

    @Override
    public ServerSocketChannel openServerSocketChannel() throws IOException {
        if (inherited != null && inherited instanceof ServerSocketChannel) {
            err.println("Returning inherited channel: " + inherited);
            return new ServerSocketChannelWrapper((ServerSocketChannel) inherited, orig);
        }
        return orig.openServerSocketChannel();
    }

    @Override
    public SocketChannel openSocketChannel() throws IOException {
        if (inherited != null && inherited instanceof SocketChannel) {
            err.println("Returning inherited channel: " + inherited);
            return (SocketChannel) inherited;
        }
        return orig.openSocketChannel();
    }

    static class ServerSocketChannelWrapper extends ServerSocketChannel implements SelChImpl {
        private final ServerSocketChannel wrapped;

        public ServerSocketChannelWrapper(ServerSocketChannel inherited, SelectorProvider provider) {
            super(provider);
            wrapped = inherited;
        }

        @Override
        public ServerSocketChannel bind(SocketAddress local, int backlog) throws IOException {
            return this;
        }

        @Override
        public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) throws IOException {
            return wrapped.setOption(name, value);
        }

        @Override
        public <T> T getOption(SocketOption<T> name) throws IOException {
            return wrapped.getOption(name);
        }

        @Override
        public Set<SocketOption<?>> supportedOptions() {
            return wrapped.supportedOptions();
        }

        @Override
        public ServerSocket socket() {
            return wrapped.socket();
        }

        @Override
        public SocketChannel accept() throws IOException {
            return wrapped.accept();
        }

        @Override
        public SocketAddress getLocalAddress() throws IOException {
            return wrapped.getLocalAddress();
        }

        @Override
        protected void implCloseSelectableChannel() throws IOException {
           //wrapped.implCloseSelectableChannel();
        }

        @Override
        protected void implConfigureBlocking(boolean block) throws IOException {
            wrapped.configureBlocking(block);
        }

        @Override
        public FileDescriptor getFD() {
            return ((SelChImpl) wrapped).getFD();
        }

        @Override
        public int getFDVal() {
            return ((SelChImpl) wrapped).getFDVal();
        }

        @Override
        public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl sk) {
            return ((SelChImpl) wrapped).translateAndUpdateReadyOps(ops, sk);
        }

        @Override
        public boolean translateAndSetReadyOps(int ops, SelectionKeyImpl sk) {
            return ((SelChImpl) wrapped).translateAndSetReadyOps(ops, sk);
        }

        @Override
        public void translateAndSetInterestOps(int ops, SelectionKeyImpl sk) {
            ((SelChImpl) wrapped).translateAndSetInterestOps(ops, sk);
        }

        @Override
        public void kill() throws IOException {
            ((SelChImpl) wrapped).kill();
        }
    }
}
