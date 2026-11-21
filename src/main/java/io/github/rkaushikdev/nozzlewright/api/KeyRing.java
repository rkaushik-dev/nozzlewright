package io.github.rkaushikdev.nozzlewright.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The API keys a gateway accepts, kept as digests rather than in the clear.
 *
 * <p>A key in the site file is a key in whatever the site backs its config up to, so the file holds
 * {@code sha256:<hex>} instead, and a copy of the file is not a copy of the key. The catch is that a
 * digest cannot be shown back to somebody who lost their key, which is the right trade: keys are
 * cheap to reissue. Plain keys are still accepted, because a site that has run for two years with
 * plain keys in its file should not have every handheld stop at an upgrade; a plain key is hashed
 * on the way in and compared the same way as any other. Keys are compared in constant time, every
 * digest every time, so how long a check takes says how many keys there are and nothing about which
 * one matched.
 *
 * @param digests the accepted keys, as stored digests
 * @param hasPlain whether any key was given in the clear
 */
public record KeyRing(Set<String> digests, boolean hasPlain) {

    /** How a hashed key is written in the site file. */
    public static final String DIGEST_PREFIX = "sha256:";

    /** Bytes of randomness in a generated key; pasted into a config once and never typed. */
    public static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Keeps the digests sorted. */
    public KeyRing {
        digests = Collections.unmodifiableSet(new TreeSet<>(digests));
    }

    /** A ring that accepts nothing, which leaves the API open. */
    public static KeyRing empty() {
        return new KeyRing(Set.of(), false);
    }

    /**
     * A ring from whatever the site file or the flags carried: digests in any case, plain keys, and
     * blanks, which are skipped.
     */
    public static KeyRing of(Iterable<String> keys) {
        Set<String> digests = new TreeSet<>();
        boolean plain = false;
        for (String raw : keys) {
            String key = raw.strip();
            if (key.isEmpty()) {
                continue;
            }
            if (key.toLowerCase(Locale.ROOT).startsWith(DIGEST_PREFIX)) {
                // Hex, so case says nothing: an editor that upper cased it left the same key.
                digests.add(key.toLowerCase(Locale.ROOT));
                continue;
            }
            plain = true;
            digests.add(hashKey(key));
        }
        return new KeyRing(digests, plain);
    }

    /** A fresh key: thirty two random bytes, URL safe so it survives being pasted anywhere. */
    public static String newKey() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The stored form of one key: its white space trimmed, hashed, prefixed. */
    public static String hashKey(String key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(key.strip().getBytes(StandardCharsets.UTF_8));
            return DIGEST_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("every Java platform carries SHA-256", error);
        }
    }

    /** True when the gateway has any keys, which is what turns authentication on. */
    public boolean any() {
        return !digests.isEmpty();
    }

    /** How many keys are accepted. */
    public int size() {
        return digests.size();
    }

    /** Whether one presented key is on the ring; a missing or blank key never is. */
    public boolean accepts(String key) {
        if (digests.isEmpty() || key == null || key.isEmpty()) {
            return false;
        }
        byte[] presented = hashKey(key).getBytes(StandardCharsets.US_ASCII);
        boolean found = false;
        for (String digest : digests) {
            found |= MessageDigest.isEqual(presented, digest.getBytes(StandardCharsets.US_ASCII));
        }
        return found;
    }

    /** What to tell somebody about how the keys are stored. */
    public String advice() {
        if (digests.isEmpty()) {
            return "no keys, the API is open";
        }
        if (hasPlain) {
            return digests.size() + " keys, some stored in the clear, run nozzlewright key hash";
        }
        return digests.size() + " keys, all hashed";
    }

    @Override
    public String toString() {
        return "KeyRing(digests=" + digests.size() + ")";
    }
}
