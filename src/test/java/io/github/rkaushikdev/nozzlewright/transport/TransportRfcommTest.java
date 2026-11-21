package io.github.rkaushikdev.nozzlewright.transport;

import static io.github.rkaushikdev.nozzlewright.testing.Wire.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LinkClosed;
import io.github.rkaushikdev.nozzlewright.TransportError;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransportRfcommTest {

    static final String PRINTER = "00:1B:DC:0F:2A:71";

    /** Stands in for the far end of a Bluetooth serial link: two connected sockets. */
    static final class PairedRadio implements AutoCloseable {
        final SocketChannel ours;
        final SocketChannel theirs;

        PairedRadio() throws IOException {
            try (ServerSocketChannel listener = ServerSocketChannel.open()) {
                listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                ours = SocketChannel.open(listener.getLocalAddress());
                theirs = listener.accept();
            }
        }

        SocketChannel factory(String address, int channel) {
            return ours;
        }

        byte[] receive(int size) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(size);
            int count = theirs.read(buffer);
            return Arrays.copyOf(buffer.array(), count);
        }

        void sendAll(byte[] data) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                theirs.write(buffer);
            }
        }

        @Override
        public void close() throws IOException {
            ours.close();
            theirs.close();
        }
    }

    private PairedRadio radio;

    @BeforeEach
    void pair() throws IOException {
        radio = new PairedRadio();
    }

    @AfterEach
    void unpair() throws IOException {
        radio.close();
    }

    RfcommTransport linkTo(PairedRadio paired) {
        return new RfcommTransport(PRINTER, 1, 8.0, 2.0, paired::factory, false);
    }

    @Test
    void addressIsNormalised() {
        RfcommTransport link = new RfcommTransport("00-1b-dc-0f-2a-71");
        assertThat(link.address()).isEqualTo("00:1B:DC:0F:2A:71");
        assertThat(link.endpoint()).isEqualTo("00:1B:DC:0F:2A:71/1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "00:1B:DC:0F:2A", "00:1B:DC:0F:2A:71:99", "zz:1B:DC:0F:2A:71", "001BDC0F2A71"})
    void badAddressesAreRefused(String bad) {
        assertThatThrownBy(() -> new RfcommTransport(bad))
                .isInstanceOf(TransportError.class)
                .hasMessageContaining("bluetooth address");
    }

    @Test
    void channelLandsInTheEndpoint() {
        assertThat(new RfcommTransport(PRINTER, 7).endpoint()).endsWith("/7");
    }

    /** The JDK has no Bluetooth socket family, so the probe says no and the platform radio refuses. */
    @Test
    void availabilityProbeMatchesThePlatform() {
        assertThat(RfcommTransport.available()).isFalse();
        RfcommTransport link = new RfcommTransport(PRINTER);
        assertThatThrownBy(link::open)
                .isInstanceOf(TransportError.class)
                .hasMessageContaining("no bluetooth socket support");
        assertThat(link.isOpen()).isFalse();
    }

    @Test
    void roundTrip() throws Exception {
        try (RfcommTransport link = linkTo(radio)) {
            link.open();
            link.send(bytes("STATUS\r\n"));
            assertThat(radio.receive(64)).isEqualTo(bytes("STATUS\r\n"));
            radio.sendAll(bytes("0:READY\r\n"));
            assertThat(link.recv(Duration.ofSeconds(1))).isEqualTo(bytes("0:READY\r\n"));
        }
    }

    @Test
    void recvTimesOutQuietly() throws Exception {
        try (RfcommTransport link = linkTo(radio)) {
            link.open();
            assertThat(link.recv(Duration.ofMillis(50))).isEmpty();
            assertThat(link.stats().readTimeouts()).isEqualTo(1);
        }
    }

    @Test
    void pairingLossRaises() throws Exception {
        try (RfcommTransport link = linkTo(radio)) {
            link.open();
            radio.theirs.close();
            assertThatThrownBy(() -> link.recv(Duration.ofSeconds(1)))
                    .isInstanceOf(LinkClosed.class)
                    .hasMessageContaining("dropped the pairing");
            assertThat(link.isOpen()).isFalse();
        }
    }

    @Test
    void countersMove() throws Exception {
        try (RfcommTransport link = linkTo(radio)) {
            link.open();
            link.send(bytes("INK\r\n"));
            radio.receive(64);
            radio.sendAll(bytes("0:\r\n"));
            link.recv(Duration.ofSeconds(1));
            assertThat(link.stats().bytesOut()).isEqualTo(5);
            assertThat(link.stats().bytesIn()).isEqualTo(4);
        }
    }

    @Test
    void sendBeforeOpenIsRefused() {
        RfcommTransport link = linkTo(radio);
        assertThatThrownBy(() -> link.send(bytes("PING\r\n"))).isInstanceOf(LinkClosed.class);
    }
}
