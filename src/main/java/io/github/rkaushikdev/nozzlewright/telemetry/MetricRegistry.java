package io.github.rkaushikdev.nozzlewright.telemetry;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Counters, gauges and histograms with labels, rendered in the Prometheus text exposition format,
 * version 0.0.4.
 *
 * <p>Each metric family is written as a {@code # HELP} line, a {@code # TYPE} line and one sample
 * line per labelled series, families in the order they were registered and series in the order they
 * first appeared. Labels are written sorted by name, with backslash, double quote and line feed
 * escaped in their values; help text has backslash and line feed escaped. Values are written in their
 * shortest exact form, {@code +Inf}, {@code -Inf} or {@code NaN}, and from a million up with a short
 * exponent ({@code 1.234567e+06}). A counter's samples end in {@code _total}. A histogram writes
 * cumulative {@code _bucket} samples with an {@code le} label, then {@code _count} and {@code _sum}.
 *
 * <p>A registry is built per site rather than shared, because a gateway that supervises two sites runs
 * two of everything and one shared registry would silently merge them. Safe to use from several
 * threads.
 */
public final class MetricRegistry {

    /** The media type the text format is served as. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final List<Family<?>> families = new ArrayList<>();

    /**
     * Register a counter, which only ever goes up.
     *
     * @param name the sample name; {@code _total} is added when it does not end with it
     */
    public synchronized Counter counter(String name, String help, String... labelNames) {
        Counter made = new Counter(name.endsWith("_total") ? name : name + "_total", help, List.of(labelNames));
        families.add(made);
        return made;
    }

    /** Register a gauge, which is set to whatever it is now. */
    public synchronized Gauge gauge(String name, String help, String... labelNames) {
        Gauge made = new Gauge(name, help, List.of(labelNames));
        families.add(made);
        return made;
    }

    /** Register a histogram over these bucket upper bounds, in ascending order; {@code +Inf} is added. */
    public synchronized Histogram histogram(String name, String help, List<Double> buckets, String... labelNames) {
        Histogram made = new Histogram(name, help, buckets, List.of(labelNames));
        families.add(made);
        return made;
    }

    /** Every family in the text exposition format. */
    public String render() {
        StringBuilder out = new StringBuilder();
        for (Family<?> family : snapshot()) {
            family.render(out);
        }
        return out.toString();
    }

    /**
     * One sample's value, found by its sample name and its exact label set.
     *
     * @return empty when no series has that name and those labels
     */
    public OptionalDouble sampleValue(String name, Map<String, String> labels) {
        for (Family<?> family : snapshot()) {
            for (Sample sample : family.samples()) {
                if (sample.name().equals(name) && sample.labels().equals(labels)) {
                    return OptionalDouble.of(sample.value());
                }
            }
        }
        return OptionalDouble.empty();
    }

    private synchronized List<Family<?>> snapshot() {
        return List.copyOf(families);
    }

    /** One line of output: a sample name, its labels and its value. */
    private record Sample(String name, Map<String, String> labels, double value) {}

    /** A named metric with labelled series of one kind. */
    private abstract static sealed class Family<S> permits Counter, Gauge, Histogram {
        private final String name;
        private final String help;
        private final List<String> labelNames;
        private final Map<List<String>, S> series = new LinkedHashMap<>();

        Family(String name, String help, List<String> labelNames) {
            this.name = name;
            this.help = help;
            this.labelNames = labelNames;
        }

        final String name() {
            return name;
        }

        /** The series for these label values, made when it does not exist yet. */
        final synchronized S child(String... values) {
            if (values.length != labelNames.size()) {
                throw new IllegalArgumentException(
                        name + " takes " + labelNames.size() + " label values, given " + values.length);
            }
            return series.computeIfAbsent(List.of(values), key -> fresh());
        }

        /**
         * Drop the series for these label values.
         *
         * @return false when there was none
         */
        public final synchronized boolean remove(String... values) {
            return series.remove(List.of(values)) != null;
        }

        abstract S fresh();

        abstract String type();

        abstract void samples(Map<String, String> labels, S child, List<Sample> into);

        final synchronized List<Sample> samples() {
            List<Sample> found = new ArrayList<>();
            series.forEach((values, child) -> {
                Map<String, String> labels = new LinkedHashMap<>();
                for (int index = 0; index < labelNames.size(); index++) {
                    labels.put(labelNames.get(index), values.get(index));
                }
                samples(labels, child, found);
            });
            return found;
        }

        final void render(StringBuilder out) {
            out.append("# HELP ")
                    .append(name)
                    .append(' ')
                    .append(escapeHelp(help))
                    .append('\n');
            out.append("# TYPE ").append(name).append(' ').append(type()).append('\n');
            for (Sample sample : samples()) {
                out.append(sample.name());
                if (!sample.labels().isEmpty()) {
                    out.append('{');
                    int written = 0;
                    for (Map.Entry<String, String> label : new TreeMap<>(sample.labels()).entrySet()) {
                        if (written++ > 0) {
                            out.append(',');
                        }
                        out.append(label.getKey())
                                .append("=\"")
                                .append(escapeLabel(label.getValue()))
                                .append('"');
                    }
                    out.append('}');
                }
                out.append(' ').append(number(sample.value())).append('\n');
            }
        }
    }

    /** One series of a counter or a gauge. */
    public static final class Value {
        private double current;

        Value() {}

        /** The current value. */
        public synchronized double get() {
            return current;
        }

        synchronized void add(double amount) {
            current += amount;
        }

        synchronized void set(double value) {
            current = value;
        }
    }

    /** A family of counters, which only ever go up. */
    public static final class Counter extends Family<Value> {

        Counter(String name, String help, List<String> labelNames) {
            super(name, help, labelNames);
        }

        /** The series for these label values. */
        public Value labels(String... values) {
            return child(values);
        }

        /** Add one to a series. */
        public void inc(String... values) {
            inc(1.0, values);
        }

        /**
         * Add to a series; adding nought makes the series appear without moving it.
         *
         * @throws IllegalArgumentException when the amount is negative
         */
        public void inc(double amount, String... values) {
            if (amount < 0) {
                throw new IllegalArgumentException("a counter can only go up, given " + Numbers.shortest(amount));
            }
            child(values).add(amount);
        }

        @Override
        Value fresh() {
            return new Value();
        }

        @Override
        String type() {
            return "counter";
        }

        @Override
        void samples(Map<String, String> labels, Value child, List<Sample> into) {
            into.add(new Sample(name(), labels, child.get()));
        }
    }

    /** A family of gauges. */
    public static final class Gauge extends Family<Value> {

        Gauge(String name, String help, List<String> labelNames) {
            super(name, help, labelNames);
        }

        /** Set a series to {@code value}. */
        public void set(double value, String... values) {
            child(values).set(value);
        }

        /** The series for these label values. */
        public Value labels(String... values) {
            return child(values);
        }

        @Override
        Value fresh() {
            return new Value();
        }

        @Override
        String type() {
            return "gauge";
        }

        @Override
        void samples(Map<String, String> labels, Value child, List<Sample> into) {
            into.add(new Sample(name(), labels, child.get()));
        }
    }

    /** One series of a histogram: a count per bucket, and the running sum. */
    public static final class Buckets {
        private final long[] counts;
        private double sum;

        Buckets(int size) {
            counts = new long[size];
        }

        synchronized void observe(List<Double> bounds, double amount) {
            sum += amount;
            for (int index = 0; index < bounds.size(); index++) {
                if (amount <= bounds.get(index)) {
                    counts[index]++;
                    return;
                }
            }
        }

        synchronized long[] counts() {
            return counts.clone();
        }

        synchronized double sum() {
            return sum;
        }
    }

    /** A family of histograms over fixed buckets. */
    public static final class Histogram extends Family<Buckets> {
        private final List<Double> bounds;

        Histogram(String name, String help, List<Double> buckets, List<String> labelNames) {
            super(name, help, labelNames);
            List<Double> all = new ArrayList<>(buckets);
            if (all.isEmpty() || all.get(all.size() - 1) != Double.POSITIVE_INFINITY) {
                all.add(Double.POSITIVE_INFINITY);
            }
            this.bounds = List.copyOf(all);
        }

        /** Count one observation into a series. */
        public void observe(double amount, String... values) {
            child(values).observe(bounds, amount);
        }

        @Override
        Buckets fresh() {
            return new Buckets(bounds.size());
        }

        @Override
        String type() {
            return "histogram";
        }

        @Override
        void samples(Map<String, String> labels, Buckets child, List<Sample> into) {
            long[] counts = child.counts();
            long running = 0;
            for (int index = 0; index < bounds.size(); index++) {
                running += counts[index];
                Map<String, String> withBound = new LinkedHashMap<>(labels);
                withBound.put("le", number(bounds.get(index)));
                into.add(new Sample(name() + "_bucket", withBound, running));
            }
            into.add(new Sample(name() + "_count", labels, running));
            into.add(new Sample(name() + "_sum", labels, child.sum()));
        }
    }

    /** A value as the text format writes it. */
    static String number(double value) {
        if (value == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        if (Double.isNaN(value)) {
            return "NaN";
        }
        String text = Numbers.shortest(value);
        int dot = text.indexOf('.');
        if (value > 0 && dot > 6) {
            String mantissa = text.charAt(0) + "." + text.substring(1, dot) + text.substring(dot + 1);
            mantissa = mantissa.replaceAll("[0.]+$", "");
            return mantissa + "e+0" + (dot - 1);
        }
        return text;
    }

    private static String escapeHelp(String text) {
        return text.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static String escapeLabel(String text) {
        return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }
}
