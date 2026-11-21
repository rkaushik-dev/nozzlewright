package io.github.rkaushikdev.nozzlewright.protocol;

import io.github.rkaushikdev.nozzlewright.FrameError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Byte level framing for the Halvex line protocol.
 *
 * <p>The protocol is line oriented ASCII. A frame is the command or the response text followed by
 * CR LF. Firmware revision 2 added an optional checksum that rides on the end of the payload as
 * {@code #HH}, where HH is the upper case hex of the XOR over every byte before the marker.
 */
public final class Framing {

    /** CR LF, what the firmware puts at the end of every frame it sends. */
    public static final byte[] TERMINATOR = {'\r', '\n'};

    /**
     * Longest frame the controller will assemble. The biggest legitimate payload is an ENUMLABELS
     * response on an eight pen machine, which tops out around 3 kB.
     */
    public static final int MAX_FRAME_BYTES = 4096;

    /** Separator between payload and checksum on revision 2 firmware. */
    public static final byte CHECKSUM_MARKER = '#';

    private Framing() {}

    /**
     * XOR every byte of {@code payload} together.
     *
     * <p>The firmware uses a plain XOR rather than a CRC because the 8 bit MCU on the HVXLCP boards
     * has no room for the table.
     */
    public static int checksum(byte[] payload) {
        int acc = 0;
        for (byte b : payload) {
            acc ^= b & 0xFF;
        }
        return acc;
    }

    /** The checksum as the two upper case hex digits the wire carries. */
    public static String checksumDigits(byte[] payload) {
        return String.format(Locale.ROOT, "%02X", checksum(payload));
    }

    /** Turn command text into the bytes to put on the wire, without a checksum. */
    public static byte[] encodeFrame(String text) {
        return encodeFrame(text, false);
    }

    /**
     * Turn command text into the bytes to put on the wire.
     *
     * @throws IllegalArgumentException when the text holds anything outside ASCII
     * @throws FrameError when the text holds a line ending or the frame would be too long
     */
    public static byte[] encodeFrame(String text, boolean withChecksum) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) > 0x7F) {
                throw new IllegalArgumentException("frame text must be ASCII, got " + Repr.of(text));
            }
        }
        byte[] payload = text.getBytes(StandardCharsets.US_ASCII);
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            throw new FrameError("frame text must not contain CR or LF", payload);
        }
        if (withChecksum) {
            byte[] digits = checksumDigits(payload).getBytes(StandardCharsets.US_ASCII);
            byte[] marked = Arrays.copyOf(payload, payload.length + 3);
            marked[payload.length] = CHECKSUM_MARKER;
            marked[payload.length + 1] = digits[0];
            marked[payload.length + 2] = digits[1];
            payload = marked;
        }
        if (payload.length + TERMINATOR.length > MAX_FRAME_BYTES) {
            throw new FrameError(
                    "frame of " + payload.length + " bytes exceeds the " + MAX_FRAME_BYTES + " byte limit",
                    Arrays.copyOf(payload, Math.min(64, payload.length)));
        }
        byte[] frame = Arrays.copyOf(payload, payload.length + TERMINATOR.length);
        System.arraycopy(TERMINATOR, 0, frame, payload.length, TERMINATOR.length);
        return frame;
    }

    /**
     * Validate and remove a trailing {@code #HH} if one is present.
     *
     * @throws FrameError when the digits are not hex or the checksum does not match
     */
    public static byte[] stripChecksum(byte[] payload) {
        int marker = lastIndexOf(payload, CHECKSUM_MARKER);
        if (marker == -1 || marker != payload.length - 3) {
            return payload.clone();
        }
        byte[] body = Arrays.copyOf(payload, marker);
        byte[] claimedText = Arrays.copyOfRange(payload, marker + 1, payload.length);
        int claimed = hexValue(claimedText);
        if (claimed < 0) {
            throw new FrameError("bad checksum digits " + Repr.bytes(claimedText), payload);
        }
        int actual = checksum(body);
        if (claimed != actual) {
            throw new FrameError(
                    String.format(
                            Locale.ROOT, "checksum mismatch, frame says %02X and we computed %02X", claimed, actual),
                    payload);
        }
        return body;
    }

    static int lastIndexOf(byte[] data, byte value) {
        for (int index = data.length - 1; index >= 0; index--) {
            if (data[index] == value) {
                return index;
            }
        }
        return -1;
    }

    /** Two hex digits read as a number, or -1 when they are not hex. */
    private static int hexValue(byte[] digits) {
        int value = 0;
        for (byte b : digits) {
            int digit = Character.digit((char) (b & 0xFF), 16);
            if (digit < 0) {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
    }
}
