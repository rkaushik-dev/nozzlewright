package io.github.rkaushikdev.nozzlewright.transport;

import io.github.rkaushikdev.nozzlewright.TransportError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Locale;

/**
 * A link over a Bluetooth serial port profile.
 *
 * <p>This is what the handheld uses on the floor. The printer advertises a serial port profile, the
 * terminal binds a channel and from there it is a byte stream like any other. RFCOMM has no
 * keepalive, so a printer that loses power looks exactly like an idle printer until something is
 * written to it, and a class 2 radio behind a stainless steel guard hands back connect errors that
 * mean nothing more than try again, so callers retry rather than give up on the machine.
 *
 * <p>The socket comes from a {@link SocketFactory}, so the test suite can hand in one end of a
 * connected pair and exercise everything above the radio. The JDK carries no Bluetooth socket
 * family, so the default factory refuses; a site that drives a radio supplies a factory for it.
 */
public final class RfcommTransport extends SocketLink {

    /** Opens a connected socket channel to a printer's radio. */
    @FunctionalInterface
    public interface SocketFactory {

        /** A connected channel to {@code address} on {@code channel}. */
        SocketChannel open(String address, int channel) throws IOException;
    }

    private static final Wording WORDING =
            new Wording("radio link is gone", "radio write blocked for", "printer dropped the pairing", 1024);

    private static final int MAC_LENGTH = 17;

    private final String address;
    private final int channel;
    private final double connectTimeoutSeconds;
    private final SocketFactory factory;

    /** A link to {@code address} on channel 1. */
    public RfcommTransport(String address) {
        this(address, 1);
    }

    /** A link to {@code address} on {@code channel}, through the platform's radio. */
    public RfcommTransport(String address, int channel) {
        this(address, channel, 8.0, 2.0, null, true);
    }

    /**
     * A link to {@code address} on {@code channel}.
     *
     * @param factory where the socket comes from; {@code null} for the platform's radio
     * @param connect true to check the address is a Bluetooth address first; false when the factory
     *     hands over a socket that is already connected, in which case the address is only upper cased
     * @throws TransportError when {@code connect} is set and the address is not a Bluetooth address
     */
    public RfcommTransport(
            String address,
            int channel,
            double connectTimeoutSeconds,
            double writeTimeoutSeconds,
            SocketFactory factory,
            boolean connect) {
        this(
                connect ? normaliseAddress(address) : address.toUpperCase(Locale.ROOT),
                channel,
                connectTimeoutSeconds,
                writeTimeoutSeconds,
                factory);
    }

    private RfcommTransport(
            String address,
            int channel,
            double connectTimeoutSeconds,
            double writeTimeoutSeconds,
            SocketFactory factory) {
        super(address + "/" + channel, writeTimeoutSeconds, WORDING);
        this.address = address;
        this.channel = channel;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.factory = factory == null ? RfcommTransport::platformSocket : factory;
    }

    /** True when this platform can open an RFCOMM socket by itself. The JDK has no such socket. */
    public static boolean available() {
        return false;
    }

    /** The radio address, upper case. */
    public String address() {
        return address;
    }

    /** The RFCOMM channel. */
    public int channel() {
        return channel;
    }

    /** How long a connect may take, in seconds. */
    public double connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    @Override
    protected void openLink() throws InterruptedException {
        SocketChannel opened = null;
        try {
            opened = factory.open(address, channel);
            attach(opened);
        } catch (IOException error) {
            TcpTransport.closeQuietly(opened);
            throw new TransportError(String.valueOf(error.getMessage()), endpoint(), error);
        }
    }

    private static SocketChannel platformSocket(String address, int channel) {
        throw new TransportError("this platform has no bluetooth socket support");
    }

    /**
     * Upper case a MAC, dashes made colons, and check it looks like one.
     *
     * @throws TransportError when it does not
     */
    static String normaliseAddress(String address) {
        String text = address.strip().toUpperCase(Locale.ROOT).replace("-", ":");
        String[] parts = text.split(":", -1);
        boolean shaped = text.length() == MAC_LENGTH && parts.length == 6;
        if (shaped) {
            for (String part : parts) {
                shaped &= part.matches("[0-9A-F]{2}");
            }
        }
        if (!shaped) {
            throw new TransportError(Repr.of(address) + " is not a bluetooth address");
        }
        return text;
    }
}
