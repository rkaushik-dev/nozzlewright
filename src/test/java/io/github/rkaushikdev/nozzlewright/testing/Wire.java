package io.github.rkaushikdev.nozzlewright.testing;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Byte literals for tests: each character below U+0100 is one byte. */
public final class Wire {

    private Wire() {}

    /** The bytes of {@code text}, one byte per character. */
    public static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The bytes as text, one character per byte. */
    public static String text(byte[] data) {
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    /** Two byte arrays end to end. */
    public static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
