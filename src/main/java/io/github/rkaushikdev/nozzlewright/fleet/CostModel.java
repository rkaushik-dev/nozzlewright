package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.printer.CartridgeSpec;

/**
 * What a site pays for ink and what its hardware does with it.
 *
 * <p>Currency is deliberately not named. A site works in one currency, the numbers that come out are
 * in whatever went in, and carrying a currency code around would invite somebody to think this
 * converts between them.
 *
 * @param cartridgePrice what one cartridge costs
 * @param spec the cartridge part
 * @param dropVolumePl how big one drop is
 * @param purgesPerThousand how often a line restarts and throws a purge, per thousand codes
 */
public record CostModel(double cartridgePrice, CartridgeSpec spec, double dropVolumePl, double purgesPerThousand) {

    /** A model with the nominal drop and a line that never stops. */
    public CostModel(double cartridgePrice, CartridgeSpec spec) {
        this(cartridgePrice, spec, Costing.NOMINAL_DROP_PL, 0.0);
    }

    /** The same model with another drop volume. */
    public CostModel withDropVolumePl(double value) {
        return new CostModel(cartridgePrice, spec, value, purgesPerThousand);
    }

    /** The same model with another purge rate. */
    public CostModel withPurgesPerThousand(double value) {
        return new CostModel(cartridgePrice, spec, dropVolumePl, value);
    }

    /**
     * Check the model before anything is quoted from it.
     *
     * @throws ConfigError when the price or the drop is not positive, the purge rate is negative or the
     *     cartridge part is unusable
     */
    public void validate() {
        if (cartridgePrice <= 0) {
            throw new ConfigError("cartridge_price", cartridgePrice, "must be positive");
        }
        if (dropVolumePl <= 0) {
            throw new ConfigError("drop_volume_pl", dropVolumePl, "must be positive");
        }
        if (purgesPerThousand < 0) {
            throw new ConfigError("purges_per_thousand", purgesPerThousand, "cannot be negative");
        }
        spec.validate();
    }

    /** How many drops a full cartridge holds. */
    public double dropsPerCartridge() {
        return spec.nominalMl() * Costing.PICOLITRES_PER_ML / dropVolumePl;
    }

    /** What one drop costs. */
    public double pricePerDrop() {
        return cartridgePrice / dropsPerCartridge();
    }

    /** What some number of drops costs. */
    public double priceOf(long drops) {
        return drops * pricePerDrop();
    }

    /** Millilitres some number of drops accounts for. */
    public double mlFor(long drops) {
        return drops * dropVolumePl / Costing.PICOLITRES_PER_ML;
    }
}
