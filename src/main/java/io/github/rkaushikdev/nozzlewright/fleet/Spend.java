package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * What a window of production cost in ink.
 *
 * <p>The keep wet ink is the spitting that keeps an idle head from drying. It is part of the cost per
 * thousand, because it is ink the site paid for to code that product, but it is not any one message's.
 *
 * @param labels one row per message that was priced, by name
 * @param unpriced messages that went out and could not be priced
 * @param idle how long the machines stood ready without printing
 * @param keepWetCost what the spitting over that idle time cost
 */
public record Spend(List<LabelSpend> labels, List<String> unpriced, Duration idle, double keepWetCost) {

    /** Copies the lists. */
    public Spend {
        labels = List.copyOf(labels);
        unpriced = List.copyOf(unpriced);
    }

    /** A spend with no idle time counted. */
    public Spend(List<LabelSpend> labels, List<String> unpriced) {
        this(labels, unpriced, Duration.ZERO, 0.0);
    }

    /** The same spend with the keep wet ink of {@code idleTime} counted under {@code model}. */
    public Spend withKeepWet(Duration idleTime, CostModel model) {
        return new Spend(labels, unpriced, idleTime, KeepWet.cost(model, idleTime));
    }

    /** Product coded across every priced message. */
    public long codes() {
        return labels.stream().mapToLong(LabelSpend::codes).sum();
    }

    /** What the ink came to, keep wet included. */
    public double cost() {
        return labels.stream().mapToDouble(LabelSpend::cost).sum() + keepWetCost;
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

    /** One line for the console, naming the keep wet share and anything that went out unpriced. */
    public String summary() {
        StringBuilder line = new StringBuilder();
        line.append(codes()).append(" codes, ").append(Numbers.fixed(cost(), 2)).append(" of ink, ");
        line.append(Numbers.fixed(costPerThousand(), 3)).append(" per thousand");
        if (keepWetCost > 0) {
            line.append(", ").append(Numbers.fixed(keepWetCost, 2)).append(" of it keeping ");
            line.append(idle.toMinutes()).append(" idle minutes wet");
        }
        if (!unpriced.isEmpty()) {
            line.append(". Not counted: ").append(String.join(", ", unpriced));
        }
        return line.toString();
    }
}
