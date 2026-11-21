package io.github.rkaushikdev.nozzlewright.transport;

import static io.github.rkaushikdev.nozzlewright.testing.Wire.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LinkClosed;
import io.github.rkaushikdev.nozzlewright.testing.Wire;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TransportLoopbackTest {

    static final Responder ECHO = data -> bytes("0:" + Wire.text(data).strip() + "\r\n");

    static final Duration HALF = Duration.ofMillis(500);
    static final Duration TINY = Duration.ofMillis(10);

    @Test
    void openAndCloseFlipTheFlag() throws Exception {
        LoopbackTransport link = new LoopbackTransport();
        assertThat(link.isOpen()).isFalse();
        link.open();
        assertThat(link.isOpen()).isTrue();
        link.close();
        assertThat(link.isOpen()).isFalse();
    }

    @Test
    void openIsIdempotent() throws Exception {
        LoopbackTransport link = new LoopbackTransport();
        link.open();
        link.open();
        assertThat(link.stats().opens()).isEqualTo(1);
    }

    @Test
    void reopenIsCounted() throws Exception {
        LoopbackTransport link = new LoopbackTransport();
        link.open();
        link.close();
        link.open();
        assertThat(link.stats().opens()).isEqualTo(2);
        assertThat(link.stats().reopens()).isEqualTo(1);
    }

    @Test
    void closeOnAClosedLinkIsQuiet() {
        LoopbackTransport link = new LoopbackTransport();
        link.close();
        assertThat(link.isOpen()).isFalse();
    }

    @Test
    void sendBeforeOpenIsRefused() {
        LoopbackTransport link = new LoopbackTransport();
        assertThatThrownBy(() -> link.send(bytes("PING\r\n"))).isInstanceOf(LinkClosed.class);
    }

    @Test
    void recvBeforeOpenIsRefused() {
        LoopbackTransport link = new LoopbackTransport();
        assertThatThrownBy(() -> link.recv(TINY)).isInstanceOf(LinkClosed.class);
    }

    @Test
    void roundTrip() throws Exception {
        try (LoopbackTransport link = new LoopbackTransport(ECHO)) {
            link.open();
            link.send(bytes("PING\r\n"));
            assertThat(link.recv(HALF)).isEqualTo(bytes("0:PING\r\n"));
        }
    }

    @Test
    void byteCountersMove() throws Exception {
        try (LoopbackTransport link = new LoopbackTransport(ECHO)) {
            link.open();
            link.send(bytes("PING\r\n"));
            link.recv(HALF);
            assertThat(link.stats().bytesOut()).isEqualTo(6);
            assertThat(link.stats().bytesIn()).isEqualTo(8);
        }
    }

    @Test
    void recvReturnsEmptyOnTimeout() throws Exception {
        try (LoopbackTransport link = new LoopbackTransport()) {
            link.open();
            assertThat(link.recv(TINY)).isEmpty();
            assertThat(link.stats().readTimeouts()).isEqualTo(1);
        }
    }

    @Test
    void fragmentedReplyArrivesInPieces() throws Exception {
        try (LoopbackTransport link = new LoopbackTransport(ECHO, "loopback", 3, Duration.ZERO)) {
            link.open();
            link.send(bytes("PING\r\n"));
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            for (int piece = 0; piece < 3; piece++) {
                joined.writeBytes(link.recv(HALF));
            }
            assertThat(joined.toByteArray()).isEqualTo(bytes("0:PING\r\n"));
        }
    }

    @Test
    void unsolicitedPushIsReadable() throws Exception {
        try (LoopbackTransport link = new LoopbackTransport()) {
            link.open();
            link.push(bytes("9:HEADFAULT\r\n"));
            assertThat(link.recv(HALF)).isEqualTo(bytes("9:HEADFAULT\r\n"));
        }
    }

    @Test
    void simulatedDropClosesTheLink() throws Exception {
        LoopbackTransport link = new LoopbackTransport(ECHO);
        link.open();
        link.dropAfter(1);
        link.send(bytes("PING\r\n"));
        assertThatThrownBy(() -> link.send(bytes("PING\r\n")))
                .isInstanceOf(LinkClosed.class)
                .hasMessageContaining("simulated");
        assertThat(link.isOpen()).isFalse();
        assertThat(link.stats().errors()).isEqualTo(1);
    }

    @Test
    void writtenFramesAreRecorded() throws Exception {
        try (LoopbackTransport link = new LoopbackTransport(ECHO)) {
            link.open();
            link.send(bytes("STATUS\r\n"));
            link.send(bytes("INK\r\n"));
            assertThat(link.written()).containsExactly(bytes("STATUS\r\n"), bytes("INK\r\n"));
        }
    }

    @Test
    void closeDrainsTheInbox() throws Exception {
        LoopbackTransport link = new LoopbackTransport();
        link.open();
        link.push(bytes("junk"));
        link.close();
        link.open();
        assertThat(link.recv(TINY)).isEmpty();
    }

    @Test
    void statsReset() throws Exception {
        try (LoopbackTransport link = new LoopbackTransport(ECHO)) {
            link.open();
            link.send(bytes("PING\r\n"));
            link.stats().reset();
            assertThat(link.stats().asMap()).containsEntry("bytes_out", 0L);
        }
    }

    @Test
    void textFormShowsState() throws Exception {
        LoopbackTransport link = new LoopbackTransport("bench");
        assertThat(link.toString()).contains("closed");
        link.open();
        assertThat(link.toString()).contains("open").contains("bench");
    }
}
