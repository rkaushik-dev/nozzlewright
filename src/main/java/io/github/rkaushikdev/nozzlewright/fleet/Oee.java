package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Overall equipment effectiveness for a coder: three factors and their product.
 *
 * <p>Every plant that asks for OEE means something slightly different by its three parts, so this is
 * the definition the controller can defend from its own readings. Availability is the share of the
 * window the machine was reachable. Performance is coded product against what the line could have
 * coded in the time it was actually printing at its rated speed, capped at one: a coder does not set
 * the line speed, so a line running faster than its rating is a rating that is wrong, not a coder
 * performing at a hundred and ten percent. Quality is coded product against product that went past,
 * and is one when nothing counts the product that went past. The whole thing is deliberately
 * conservative: a number that flatters the coder gets quoted once and then never trusted again.
 *
 * @param availability the reachable share of the window, nought to one
 * @param performance coded against what could have been coded, nought to one
 * @param quality coded against what went past, nought to one
 */
public record Oee(double availability, double performance, double quality) {

    /** The three multiplied together. */
    public double overall() {
        return availability * performance * quality;
    }

    /** Which of the three is dragging, so a meeting has somewhere to start; on a tie the earlier one. */
    public String worstFactor() {
        String worst = "availability";
        double lowest = availability;
        if (performance < lowest) {
            worst = "performance";
            lowest = performance;
        }
        if (quality < lowest) {
            worst = "quality";
        }
        return worst;
    }

    /** A plain mapping for the console and the API, each figure to four places. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("availability", Numbers.round(availability, 4));
        body.put("performance", Numbers.round(performance, 4));
        body.put("quality", Numbers.round(quality, 4));
        body.put("overall", Numbers.round(overall(), 4));
        body.put("worst_factor", worstFactor());
        return body;
    }

    /** One line, as percentages: {@code OEE 50.0%  A 100.0%  P 50.0%  Q 100.0%}. */
    public String render() {
        return "OEE " + percent(overall()) + "  A " + percent(availability) + "  P " + percent(performance) + "  Q "
                + percent(quality);
    }

    private static String percent(double fraction) {
        return Numbers.fixed(fraction * 100, 1) + "%";
    }
}
