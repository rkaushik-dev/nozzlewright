package io.github.rkaushikdev.nozzlewright.labels;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A message moved down the head, and what it would print like there.
 *
 * @param offsetDots how many dot rows further down the head the message starts
 * @param verification the verdict at that offset
 */
public record RowShift(int offsetDots, Verification verification) {

    /**
     * True when this offset prints better than {@code other}: better legibility, then fewer dots
     * lost, then a smaller move.
     */
    public boolean betterThan(RowShift other) {
        int rank = Integer.compare(
                verification.legibility().rank(),
                other.verification.legibility().rank());
        if (rank != 0) {
            return rank < 0;
        }
        int lost = Integer.compare(verification.dotsLost(), other.verification.dotsLost());
        if (lost != 0) {
            return lost < 0;
        }
        return offsetDots < other.offsetDots;
    }

    /** A plain mapping for the console and the API. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shift_dots", offsetDots);
        body.put("shift_legibility", verification.legibility().value());
        body.put("shift_dots_lost", verification.dotsLost());
        return body;
    }
}
