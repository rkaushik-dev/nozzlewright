package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.Comparator;
import java.util.List;

/**
 * What a window of production cost in ink.
 *
 * @param labels one row per message that was priced, by name
 * @param unpriced messages that went out and could not be priced
 */
public record Spend(List<LabelSpend> labels, List<String> unpriced) {

    /** Copies the lists. */
    public Spend {
        labels = List.copyOf(labels);
        unpriced = List.copyOf(unpriced);
    }

    /** Product coded across every priced message. */
    public long codes() {
        return labels.stream().mapToLong(LabelSpend::codes).sum();
    }

    /** What the ink came to. */
    public double cost() {
        double total = 0.0;
        for (LabelSpend row : labels) {
            total += row.cost();
        }
        return total;
    }

    /** The number a plant manager compares against the tender; nought with nothing coded. */
    public double costPerThousand() {
        return codes() == 0 ? 0.0 : cost() / codes() * 1000.0;
    }

    /** Messages sorted by what they cost, dearest first. */
    public List<LabelSpend> dearest() {
        return labels.stream()
                .sorted(Comparator.comparingDouble(LabelSpend::cost).reversed())
                .toList();
    }

    /** One line for the console, naming anything that went out unpriced. */
    public String summary() {
        String head = codes() + " codes, " + Numbers.fixed(cost(), 2) + " of ink, "
                + Numbers.fixed(costPerThousand(), 3) + " per thousand";
        if (!unpriced.isEmpty()) {
            return head + ". Not counted: " + String.join(", ", unpriced);
        }
        return head;
    }
}
