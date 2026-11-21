package io.github.rkaushikdev.nozzlewright.protocol;

import static io.github.rkaushikdev.nozzlewright.testing.Wire.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.FrameError;
import io.github.rkaushikdev.nozzlewright.testing.Wire;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FramingTest {

    @Test
    void encodeAppendsCrlf() {
        assertThat(Framing.encodeFrame("STATUS")).isEqualTo(bytes("STATUS\r\n"));
    }

    @Test
    void encodeWithChecksum() {
        byte[] raw = Framing.encodeFrame("PING", true);
        String text = Wire.text(raw);
        assertThat(text).endsWith("\r\n");
        String body = text.substring(0, text.length() - 2);
        assertThat(body).startsWith("PING#");
        assertThat(Integer.parseInt(body.substring(body.length() - 2), 16)).isEqualTo(Framing.checksum(bytes("PING")));
    }

    @Test
    void encodeRejectsEmbeddedTerminator() {
        assertThatThrownBy(() -> Framing.encodeFrame("STATUS\rSTATUS")).isInstanceOf(FrameError.class);
    }

    @Test
    void encodeRejectsOversize() {
        assertThatThrownBy(() -> Framing.encodeFrame("X".repeat(Framing.MAX_FRAME_BYTES + 1)))
                .isInstanceOf(FrameError.class);
    }

    @Test
    void encodeRejectsNonAscii() {
        assertThatThrownBy(() -> Framing.encodeFrame("LABEL:café")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checksumIsXor() {
        assertThat(Framing.checksum(new byte[0])).isZero();
        assertThat(Framing.checksum(new byte[] {1, 2})).isEqualTo(3);
        assertThat(Framing.checksum(bytes("AA"))).isZero();
    }

    @Test
    void stripChecksumRoundTrip() {
        byte[] body = bytes("0:READY");
        byte[] framed = bytes("0:READY#" + Framing.checksumDigits(body));
        assertThat(Framing.stripChecksum(framed)).isEqualTo(body);
    }

    @Test
    void stripChecksumDetectsCorruption() {
        assertThatThrownBy(() -> Framing.stripChecksum(bytes("0:READY#00")))
                .isInstanceOf(FrameError.class)
                .hasMessageContaining("mismatch");
    }

    @Test
    void stripChecksumRejectsBadDigits() {
        assertThatThrownBy(() -> Framing.stripChecksum(bytes("0:READY#ZZ")))
                .isInstanceOf(FrameError.class)
                .hasMessageContaining("bad checksum");
    }

    @Test
    void stripChecksumLeavesPlainPayload() {
        assertThat(Framing.stripChecksum(bytes("0:READY"))).isEqualTo(bytes("0:READY"));
    }

    @Test
    void bufferReassemblesSplitFrame() {
        FrameBuffer buffer = new FrameBuffer();
        assertThat(buffer.feed(bytes("0:RE"))).isEmpty();
        assertThat(buffer.pending()).isEqualTo(4);
        assertThat(buffer.feed(bytes("ADY\r\n"))).containsExactly(bytes("0:READY"));
        assertThat(buffer.pending()).isZero();
    }

    @Test
    void bufferHandlesBareLf() {
        FrameBuffer buffer = new FrameBuffer();
        assertThat(buffer.feed(bytes("0:OK\n1:BUSY\n"))).containsExactly(bytes("0:OK"), bytes("1:BUSY"));
    }

    @Test
    void bufferSkipsEmptyLines() {
        FrameBuffer buffer = new FrameBuffer();
        assertThat(buffer.feed(bytes("\r\n\r\n0:OK\r\n"))).containsExactly(bytes("0:OK"));
    }

    @Test
    void bufferStripsBootNoise() {
        FrameBuffer buffer = new FrameBuffer();
        assertThat(buffer.feed(bytes("\u0000\u0000ÿ0:OK\r\n"))).containsExactly(bytes("0:OK"));
        assertThat(buffer.dropped()).isEqualTo(3);
    }

    @Test
    void bufferResyncsWhenItCannotFindATerminator() {
        FrameBuffer buffer = new FrameBuffer();
        assertThat(buffer.feed(filled(Framing.MAX_FRAME_BYTES + 8))).isEmpty();
        assertThat(buffer.resyncs()).isEqualTo(1);
        assertThat(buffer.feed(Framing.encodeFrame("0:OK"))).containsExactly(bytes("0:OK"));
    }

    @Test
    void bufferGivesUpOnALinkThatNeverSyncs() {
        FrameBuffer buffer = new FrameBuffer(true, 2);
        assertThatThrownBy(() -> {
                    for (int round = 0; round < 3; round++) {
                        buffer.feed(filled(Framing.MAX_FRAME_BYTES + 8));
                    }
                })
                .isInstanceOf(FrameError.class)
                .hasMessageContaining("not speaking the protocol");
    }

    @Test
    void bufferResetCountsDiscarded() {
        FrameBuffer buffer = new FrameBuffer();
        buffer.feed(bytes("partial"));
        buffer.reset();
        assertThat(buffer.pending()).isZero();
        assertThat(buffer.dropped()).isEqualTo(7);
    }

    @Test
    void bufferCanSkipChecksumVerification() {
        FrameBuffer buffer = new FrameBuffer(false, 4);
        assertThat(buffer.feed(bytes("0:OK#00\r\n"))).containsExactly(bytes("0:OK"));
    }

    @Test
    void bufferKeepsTabInsideStatisticsPayload() {
        FrameBuffer buffer = new FrameBuffer();
        assertThat(buffer.feed(bytes("0:PRINTS\t1204\r\n"))).containsExactly(bytes("0:PRINTS\t1204"));
    }

    static byte[] filled(int size) {
        byte[] data = new byte[size];
        Arrays.fill(data, (byte) 'A');
        return data;
    }
}
