package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.labels.DotMatrix;
import io.github.rkaushikdev.nozzlewright.printer.InkLedger;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Quotients;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What the coding actually costs.
 *
 * <p>The question a plant manager asks about a coder is never about nozzles. It is what it costs to
 * code a carton, and whether the number somebody quoted at the tender is what is happening on the
 * floor. That number is arithmetic on things the controller already knows: how many dots a label lays
 * down, how big a drop is, what a cartridge holds, and what one costs.
 *
 * <p>Two habits make the answer wrong, and both are avoided here. The first is quoting cost per
 * cartridge: cartridges last a fixed number of drops, not a fixed time, and a message with a barcode
 * on it uses four times the ink of a date code, so the unit that means anything is cost per thousand
 * codes, and it belongs to a label rather than to a machine. The second is forgetting the purges: a
 * line that stops for twenty minutes every changeover spits a full purge on restart, and on a short
 * run day that is a real share of the ink, so it is counted separately. Nothing here is per unit of
 * time; a cartridge's service life is the cartridge's business.
 */
public final class Costing {

    /** Picolitres in a millilitre. */
    public static final double PICOLITRES_PER_ML = 1_000_000_000.0;

    /** A typical drop off a 300 dpi thermal cartridge. */
    public static final double NOMINAL_DROP_PL = 42.0;

    private Costing() {}

    /**
     * How many drops one rendered message throws.
     *
     * <p>One set dot is one drop. That is exactly true on a thermal head and it is the reason this
     * number can be trusted: there is no coverage estimate and no fudge factor, the raster is the
     * firing pattern.
     */
    public static long dropsIn(DotMatrix matrix) {
        return matrix.dotCount();
    }

    /**
     * What one rendered message costs to run.
     *
     * <p>The codes per cartridge figure counts the purges against the cartridge as well, so it is the
     * number a store person can order against rather than the optimistic one off a datasheet.
     *
     * @throws ConfigError when the model is unusable or the message renders to nothing
     */
    public static LabelCost costOfLabel(String label, DotMatrix matrix, CostModel model) {
        model.validate();
        long drops = dropsIn(matrix);
        if (drops == 0) {
            throw new ConfigError("label", label, "renders to nothing, there is no cost to quote");
        }
        double purgeDropsPerThousand = model.purgesPerThousand() * model.spec().purgeDrops();
        double printCost = model.priceOf(drops * 1000);
        double purgeCost = model.priceOf((long) purgeDropsPerThousand);
        double perCodeDrops = drops + purgeDropsPerThousand / 1000.0;
        return new LabelCost(
                label, drops, printCost, purgeCost, (long) Quotients.floor(model.dropsPerCartridge(), perCodeDrops));
    }

    /**
     * What a window of production cost, by message, in name order.
     *
     * <p>{@code printed} is how many of each message went out, which comes off the run records.
     * {@code drops} is how many drops each one throws, which comes off rendering it. A message that was
     * printed and cannot be rendered is named rather than guessed at: a label deleted from the store
     * since the run is exactly the case, and inventing a dot count for it would quietly move the cost
     * per thousand.
     *
     * @throws ConfigError when the model is unusable
     */
    public static Spend spendOver(Map<String, Long> printed, Map<String, Long> drops, CostModel model) {
        model.validate();
        List<LabelSpend> rows = new ArrayList<>();
        List<String> unpriced = new ArrayList<>();
        for (Map.Entry<String, Long> entry : new TreeMap<>(printed).entrySet()) {
            String label = entry.getKey();
            long codes = entry.getValue();
            long perCode = drops.getOrDefault(label, 0L);
            if (perCode <= 0 || codes <= 0) {
                if (codes > 0) {
                    unpriced.add(label);
                }
                continue;
            }
            rows.add(new LabelSpend(label, codes, perCode, model.priceOf(codes * perCode)));
        }
        return new Spend(rows, unpriced);
    }

    /** How much longer the cartridge in the machine will last, with no rate to put a time on it. */
    public static RunForecast forecast(InkLedger ledger, CostModel model, long dropsPerCode) {
        return forecast(ledger, model, dropsPerCode, 0.0);
    }

    /**
     * How much longer the cartridge in the machine will last.
     *
     * <p>Works off the ledger of what has actually been thrown rather than off the level the machine
     * reports. The machine estimates its level from the same drop count, so the two agree, and the
     * ledger is the one that survives a cartridge being moved between machines. A spent cartridge has
     * nothing and no time left; without a positive rate there is no time to give.
     *
     * @throws ConfigError when the model is unusable or a code would throw no drops
     */
    public static RunForecast forecast(InkLedger ledger, CostModel model, long dropsPerCode, double codesPerHour) {
        model.validate();
        if (dropsPerCode <= 0) {
            throw new ConfigError("drops_per_code", dropsPerCode, "a code has to throw at least one drop");
        }
        double left = model.dropsPerCartridge() - ledger.totalDrops();
        if (left <= 0) {
            return new RunForecast(0, 0.0, Optional.of(Duration.ZERO));
        }
        long codes = (long) Quotients.floor(left, dropsPerCode);
        double mlLeft = left * model.dropVolumePl() / PICOLITRES_PER_ML;
        if (codesPerHour <= 0) {
            return new RunForecast(codes, mlLeft);
        }
        long micros = Numbers.round(codes / codesPerHour * 3600 * 1_000_000);
        return new RunForecast(codes, mlLeft, Optional.of(Duration.of(micros, ChronoUnit.MICROS)));
    }
}
