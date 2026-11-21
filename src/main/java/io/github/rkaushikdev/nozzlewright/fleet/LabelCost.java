package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one message costs to print.
 *
 * @param label the message
 * @param dropsPerCode drops one code throws
 * @param printCostPerThousand ink on product, per thousand codes
 * @param purgeCostPerThousand ink on purges, per thousand codes
 * @param codesPerCartridge codes one cartridge does, purges counted against it
 */
public record LabelCost(
        String label,
        long dropsPerCode,
        double printCostPerThousand,
        double purgeCostPerThousand,
        long codesPerCartridge) {

    /** Everything it costs to put a thousand of these on product. */
    public double costPerThousand() {
        return printCostPerThousand + purgeCostPerThousand;
    }

    /** The same, per carton. */
    public double costPerCode() {
        return costPerThousand() / 1000.0;
    }

    /** How much of the cost went on the floor rather than on product, as a percentage. */
    public double purgeSharePct() {
        if (costPerThousand() == 0) {
            return 0.0;
        }
        return purgeCostPerThousand / costPerThousand() * 100.0;
    }

    /** A plain mapping for the console and the API. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", label);
        body.put("drops_per_code", dropsPerCode);
        body.put("cost_per_thousand", Numbers.round(costPerThousand(), 4));
        body.put("cost_per_code", Numbers.round(costPerCode(), 6));
        body.put("purge_share_pct", Numbers.round(purgeSharePct(), 1));
        body.put("codes_per_cartridge", codesPerCartridge);
        return body;
    }
}
