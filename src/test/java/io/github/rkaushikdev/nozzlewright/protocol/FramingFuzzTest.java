package io.github.rkaushikdev.nozzlewright.protocol;

import static io.github.rkaushikdev.nozzlewright.protocol.FramingTest.filled;
import static io.github.rkaushikdev.nozzlewright.testing.Wire.bytes;
import static io.github.rkaushikdev.nozzlewright.testing.Wire.concat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.FrameError;
import io.github.rkaushikdev.nozzlewright.testing.Wire;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Feeding the frame buffer things a printer should never send.
 *
 * <p>A seeded generator and a few thousand ugly inputs. What is checked is narrow: the buffer
 * either yields a frame or does not, it never returns something that was not sent, and it never
 * wedges. Everything a machine does when its power supply is dying looks like one of these.
 */
class FramingFuzzTest {

    static byte[] noise(Random rng, int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < size; index++) {
            data[index] = (byte) rng.nextInt(256);
        }
        return data;
    }

    static byte[] without(byte[] data, byte... unwanted) {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        for (byte b : data) {
            boolean drop = false;
            for (byte bad : unwanted) {
                drop |= b == bad;
            }
            if (!drop) {
                kept.write(b);
            }
        }
        return kept.toByteArray();
    }

    @Test
    void randomBytesNeverWedgeTheBuffer() {
        Random rng = new Random(20250416);
        for (int round = 0; round < 400; round++) {
            FrameBuffer buffer = new FrameBuffer(false, 1000);
            byte[] chunk = noise(rng, rng.nextInt(201));
            List<byte[]> frames;
            try {
                frames = buffer.feed(chunk);
            } catch (FrameError error) {
                continue;
            }
            for (byte[] frame : frames) {
                for (byte b : frame) {
                    int value = b & 0xFF;
                    assertThat(value).isNotEqualTo('\n').isNotEqualTo('\r');
                    assertThat((value >= 0x20 && value < 0x7F) || value == 0x09).isTrue();
                }
            }
        }
    }

    @Test
    void aWellFormedFrameSurvivesBeingBuriedInNoise() {
        Random rng = new Random(4417);
        for (int round = 0; round < 200; round++) {
            FrameBuffer buffer = new FrameBuffer(false, 1000);
            String payload = "0:STATE=READY";
            byte[] stream = concat(without(noise(rng, rng.nextInt(41)), (byte) '\n'), Framing.encodeFrame(payload));
            List<byte[]> frames = buffer.feed(stream);
            assertThat(frames).isNotEmpty();
            assertThat(Wire.text(frames.get(frames.size() - 1))).endsWith(payload);
        }
    }

    @Test
    void aFrameSplitAtEveryPositionComesOutWhole() {
        byte[] raw = Framing.encodeFrame("0:FAM=HVX8PEN,REV=2");
        for (int cut = 0; cut <= raw.length; cut++) {
            FrameBuffer buffer = new FrameBuffer();
            List<byte[]> frames = new ArrayList<>(buffer.feed(Arrays.copyOf(raw, cut)));
            frames.addAll(buffer.feed(Arrays.copyOfRange(raw, cut, raw.length)));
            assertThat(frames).containsExactly(bytes("0:FAM=HVX8PEN,REV=2"));
        }
    }

    @Test
    void oneByteAtATime() {
        byte[] raw = concat(Framing.encodeFrame("0:OK"), Framing.encodeFrame("1:BUSY"));
        FrameBuffer buffer = new FrameBuffer();
        List<byte[]> frames = new ArrayList<>();
        for (int index = 0; index < raw.length; index++) {
            frames.addAll(buffer.feed(Arrays.copyOfRange(raw, index, index + 1)));
        }
        assertThat(frames).containsExactly(bytes("0:OK"), bytes("1:BUSY"));
    }

    @Test
    void aLongRunWithoutATerminatorResyncsRatherThanRaising() {
        FrameBuffer buffer = new FrameBuffer(true, 3);
        assertThat(buffer.feed(filled(Framing.MAX_FRAME_BYTES + 10))).isEmpty();
        assertThat(buffer.resyncs()).isEqualTo(1);
        assertThat(buffer.pending()).isZero();
        assertThat(buffer.feed(Framing.encodeFrame("0:OK"))).containsExactly(bytes("0:OK"));
    }

    @Test
    void aLinkThatNeverSyncsIsReported() {
        FrameBuffer buffer = new FrameBuffer(true, 2);
        assertThatThrownBy(() -> {
                    for (int round = 0; round < 4; round++) {
                        buffer.feed(filled(Framing.MAX_FRAME_BYTES + 10));
                    }
                })
                .isInstanceOf(FrameError.class)
                .hasMessageContaining("not speaking the protocol");
    }

    @Test
    void aGoodFrameClearsTheResyncRun() {
        FrameBuffer buffer = new FrameBuffer(true, 2);
        buffer.feed(filled(Framing.MAX_FRAME_BYTES + 10));
        buffer.feed(Framing.encodeFrame("0:OK"));
        buffer.feed(filled(Framing.MAX_FRAME_BYTES + 10));
        assertThat(buffer.resyncs()).isEqualTo(2);
    }

    @Test
    void resetClearsTheResyncRun() {
        FrameBuffer buffer = new FrameBuffer(true, 2);
        buffer.feed(filled(Framing.MAX_FRAME_BYTES + 10));
        buffer.reset();
        buffer.feed(filled(Framing.MAX_FRAME_BYTES + 10));
        assertThat(buffer.resyncs()).isEqualTo(2);
    }

    @Test
    void aBootBannerThenAFrame() {
        byte[] banner = bytes("\u0000ÿþHALVEX BOOT 2.4.118\u0000\r\n");
        FrameBuffer buffer = new FrameBuffer();
        List<byte[]> frames = buffer.feed(concat(banner, Framing.encodeFrame("0:READY")));
        assertThat(frames.get(frames.size() - 1)).isEqualTo(bytes("0:READY"));
        assertThat(Wire.text(frames.get(0))).contains("HALVEX BOOT 2.4.118");
    }

    @Test
    void checksumsSurviveTheRoundTripForEveryPayloadLength() {
        for (int length = 1; length < 200; length++) {
            byte[] payload = bytes("0:" + "X".repeat(length));
            byte[] framed = concat(payload, bytes("#" + Framing.checksumDigits(payload)));
            assertThat(Framing.stripChecksum(framed)).isEqualTo(payload);
        }
    }

    @Test
    void aCorruptedChecksumIsCaughtWhereverTheBitFlips() {
        byte[] payload = bytes("0:STATE=READY");
        byte[] framed = concat(payload, bytes("#" + Framing.checksumDigits(payload)));
        int caught = 0;
        for (int index = 0; index < payload.length; index++) {
            byte[] broken = framed.clone();
            broken[index] ^= 0x01;
            try {
                Framing.stripChecksum(broken);
            } catch (FrameError error) {
                caught++;
            }
        }
        assertThat(caught).isEqualTo(payload.length);
    }

    @Test
    void repeatedTerminatorsYieldNothingExtra() {
        FrameBuffer buffer = new FrameBuffer();
        assertThat(buffer.feed(bytes("\r\n".repeat(50)))).isEmpty();
        assertThat(buffer.pending()).isZero();
    }

    @Test
    void aFrameOfOnlyNoiseIsDropped() {
        FrameBuffer buffer = new FrameBuffer();
        assertThat(buffer.feed(new byte[] {0, 1, 2, '\r', '\n'})).isEmpty();
        assertThat(buffer.dropped()).isEqualTo(3);
    }

    @Test
    void interleavedGoodAndBadFrames() {
        Random rng = new Random(99);
        FrameBuffer buffer = new FrameBuffer(false, 1000);
        List<byte[]> expected = new ArrayList<>();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (int index = 0; index < 50; index++) {
            if (index % 3 == 0) {
                byte[] junk = without(noise(rng, 20), (byte) '\n', (byte) '\r');
                stream.writeBytes(concat(junk, bytes("\r\n")));
            } else {
                String payload = "0:N=" + index;
                expected.add(bytes(payload));
                stream.writeBytes(Framing.encodeFrame(payload));
            }
        }
        List<byte[]> frames = buffer.feed(stream.toByteArray());
        for (byte[] wanted : expected) {
            assertThat(frames).anySatisfy(frame -> assertThat(frame).isEqualTo(wanted));
        }
    }
}
