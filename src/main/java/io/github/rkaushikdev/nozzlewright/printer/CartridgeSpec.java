package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;

/**
 * A cartridge part, as it comes off the shelf.
 *
 * @param partNumber the part number printed on the box
 * @param family what the ink is carried in
 * @param nominalMl the fill when new
 * @param rows nozzle rows, one to four
 * @param nozzlesPerRow nozzles on each row
 */
public record CartridgeSpec(String partNumber, InkFamily family, double nominalMl, int rows, int nozzlesPerRow) {

    /** Drops each nozzle throws during a full purge. */
    public static final int PURGE_DROPS_PER_NOZZLE = 240;

    /** A standard 42 ml, two row, 52 nozzle part. */
    public CartridgeSpec(String partNumber, InkFamily family) {
        this(partNumber, family, 42.0, 2, Head.NOZZLES_PER_ROW);
    }

    /** The same part with another fill. */
    public CartridgeSpec withNominalMl(double value) {
        return new CartridgeSpec(partNumber, family, value, rows, nozzlesPerRow);
    }

    /** The same part with another row count. */
    public CartridgeSpec withRows(int value) {
        return new CartridgeSpec(partNumber, family, nominalMl, value, nozzlesPerRow);
    }

    /** Every nozzle on the part. */
    public int nozzleCount() {
        return rows * nozzlesPerRow;
    }

    /**
     * Drops a full purge throws.
     *
     * <p>Purges are not small: a full purge on a four row cartridge throws more than a thousand
     * labels worth of ink, which is why the ledger counts them apart from printing.
     */
    public int purgeDrops() {
        return nozzleCount() * PURGE_DROPS_PER_NOZZLE;
    }

    /**
     * Check the part definition.
     *
     * @throws ConfigError when the part number is blank, the fill is not positive or the rows are out
     *     of range
     */
    public void validate() {
        if (partNumber.isBlank()) {
            throw new ConfigError("cartridge.part_number", partNumber, "a part needs a number");
        }
        if (nominalMl <= 0) {
            throw new ConfigError("cartridge.nominal_ml", nominalMl, "volume must be positive");
        }
        if (rows < 1 || rows > 4) {
            throw new ConfigError("cartridge.rows", rows, "a cartridge carries one to four rows");
        }
    }
}
