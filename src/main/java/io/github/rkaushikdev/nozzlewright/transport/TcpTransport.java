package io.github.rkaushikdev.nozzlewright.transport;

import io.github.rkaushikdev.nozzlewright.ConnectTimeout;
import io.github.rkaushikdev.nozzlewright.TransportError;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import jdk.net.ExtendedSocketOptions;

/**
 * A link over a plain TCP socket.
 *
 * <p>The line gateway reaches printers over the plant network. Every Halvex board with an ethernet
 * option listens on 9100, the port a raw print queue would use, and speaks the same line protocol
 * it speaks over Bluetooth. Plant switches are happy to accept a connection to a machine that has
 * been powered off for a week, so keepalives are on and set aggressively: a dead peer is noticed
 * inside roughly fifteen seconds.
 */
public final class TcpTransport extends SocketLink {

    /** The port a Halvex board listens on out of the box. */
    public static final int DEFAULT_PORT = 9100;

    private static final int KEEPALIVE_IDLE_S = 5;
    private static final int KEEPALIVE_INTERVAL_S = 5;
    private static final int KEEPALIVE_COUNT = 2;

    private static final Wording WORDING =
            new Wording("socket is gone", "write blocked for", "far end closed the connection", 4096);

    private final String host;
    private final int port;
    private final double connectTimeoutSeconds;
    private final double writeTimeoutSeconds;

    /** A link to {@code host} on the default port. */
    public TcpTransport(String host) {
        this(host, DEFAULT_PORT);
    }

    /** A link to {@code host} on {@code port}, with the default timeouts. */
    public TcpTransport(String host, int port) {
        this(host, port, 6.0, 2.0);
    }

    /** A link to {@code host} on {@code port}. */
    public TcpTransport(String host, int port, double connectTimeoutSeconds, double writeTimeoutSeconds) {
        super(host + ":" + port, writeTimeoutSeconds, WORDING);
        this.host = host;
        this.port = port;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.writeTimeoutSeconds = writeTimeoutSeconds;
    }

    /** The host name or address. */
    public String host() {
        return host;
    }

    /** The port. */
    public int port() {
        return port;
    }

    /** How long a connect may take, in seconds. */
    public double connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    /** How long a write may block, in seconds. */
    public double writeTimeoutSeconds() {
        return writeTimeoutSeconds;
    }

    @Override
    protected void openLink() throws InterruptedException {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            if (!channel.connect(new InetSocketAddress(host, port))) {
                try (Selector selector = Selector.open()) {
                    channel.register(selector, SelectionKey.OP_CONNECT);
                    int ready = selector.select(Durations.waitMillis(Durations.ofSeconds(connectTimeoutSeconds)));
                    if (Thread.interrupted()) {
                        throw new InterruptedException("connect interrupted");
                    }
                    if (ready == 0) {
                        throw new ConnectTimeout(
                                "no answer inside " + Durations.tenths(connectTimeoutSeconds), endpoint());
                    }
                }
                channel.finishConnect();
            }
            enableKeepalive(channel);
            attach(channel);
        } catch (IOException | UnresolvedAddressException error) {
            closeQuietly(channel);
            throw new TransportError(describe(error), endpoint(), error);
        } catch (ConnectTimeout | InterruptedException error) {
            closeQuietly(channel);
            throw error;
        }
    }

    /**
     * Turn on TCP keepalive with a short idle time, where the platform allows.
     *
     * <p>The idle, interval and count options are missing on some platforms, so each one is set on
     * its own and a missing option is not an error.
     */
    private static void enableKeepalive(SocketChannel channel) {
        try {
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        } catch (IOException | UnsupportedOperationException error) {
            return;
        }
        setIfSupported(channel, ExtendedSocketOptions.TCP_KEEPIDLE, KEEPALIVE_IDLE_S);
        setIfSupported(channel, ExtendedSocketOptions.TCP_KEEPINTERVAL, KEEPALIVE_INTERVAL_S);
        setIfSupported(channel, ExtendedSocketOptions.TCP_KEEPCOUNT, KEEPALIVE_COUNT);
    }

    private static void setIfSupported(SocketChannel channel, SocketOption<Integer> option, int value) {
        if (!channel.supportedOptions().contains(option)) {
            return;
        }
        try {
            channel.setOption(option, value);
        } catch (IOException | UnsupportedOperationException error) {
            // The platform carries the option but refused this value; the defaults still apply.
            return;
        }
    }

    private static String describe(Exception error) {
        if (error instanceof UnresolvedAddressException) {
            return "host name could not be resolved";
        }
        return String.valueOf(error.getMessage());
    }

    static void closeQuietly(SocketChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException error) {
            // The connect already failed; a failed close changes nothing.
            return;
        }
    }
}
