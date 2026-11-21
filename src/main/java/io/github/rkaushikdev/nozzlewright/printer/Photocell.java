package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;

/**
 * The product detect input.
 *
 * @param channel the input the photocell is wired to, one to four
 * @param mode how the signal is read
 * @param maskDots travel after a trigger during which a second trigger is ignored
 * @param delayDots travel between the trigger and the first column
 */
public record Photocell(int channel, PhotocellMode mode, int maskDots, int delayDots) {

    private static final double SECONDS_PER_MINUTE = 60.0;

    /** Channel one, edge triggered, no mask and no delay. */
    public Photocell() {
        this(1, PhotocellMode.EDGE, 0, 0);
    }

    /** The same photocell on another input. */
    public Photocell withChannel(int value) {
        return new Photocell(value, mode, maskDots, delayDots);
    }

    /** The same photocell read another way. */
    public Photocell withMode(PhotocellMode value) {
        return new Photocell(channel, value, maskDots, delayDots);
    }

    /** The same photocell with another mask. */
    public Photocell withMaskDots(int value) {
        return new Photocell(channel, mode, value, delayDots);
    }

    /** The same photocell with another delay. */
    public Photocell withDelayDots(int value) {
        return new Photocell(channel, mode, maskDots, value);
    }

    /** Distance between the trigger and the first column. */
    public double delayMm(int horizontalDpi) {
        return delayDots * Head.MM_PER_INCH / horizontalDpi;
    }

    /** Distance after the trigger where a second trigger is ignored. */
    public double maskMm(int horizontalDpi) {
        return maskDots * Head.MM_PER_INCH / horizontalDpi;
    }

    /**
     * True when the mask window swallows a contact bounce of {@code bounceMs}.
     *
     * <p>A worn photocell on a shiny product retriggers within a few milliseconds. The mask has to be
     * longer than that at the line speed in use, and the speed is what makes this a moving target.
     */
    public boolean rejectsBounceAt(double speedMmPerMin, double bounceMs, int dpi) {
        if (bounceMs <= 0) {
            return true;
        }
        double travelMm = speedMmPerMin / SECONDS_PER_MINUTE / 1000.0 * bounceMs;
        return maskMm(dpi) >= travelMm;
    }

    /**
     * Check the photocell settings.
     *
     * @throws ConfigError when the channel or a counter is out of range, or a gated input has no mask
     */
    public void validate() {
        if (channel < 1 || channel > 4) {
            throw new ConfigError("photocell.channel", channel, "there are four inputs");
        }
        if (maskDots < 0 || maskDots > 65_535) {
            throw new ConfigError("photocell.mask_dots", maskDots, "the mask counter is sixteen bits");
        }
        if (delayDots < 0 || delayDots > 65_535) {
            throw new ConfigError("photocell.delay_dots", delayDots, "the delay counter is sixteen bits");
        }
        if (mode == PhotocellMode.GATED && maskDots == 0) {
            throw new ConfigError(
                    "photocell.mask_dots", maskDots, "gated mode without a mask retriggers on the trailing edge");
        }
    }
}
