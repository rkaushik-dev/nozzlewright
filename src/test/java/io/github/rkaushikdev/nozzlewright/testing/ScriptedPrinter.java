package io.github.rkaushikdev.nozzlewright.testing;

import io.github.rkaushikdev.nozzlewright.protocol.Framing;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import io.github.rkaushikdev.nozzlewright.transport.Responder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replies from a script, with a default for anything unscripted.
 *
 * <p>Deliberately dumb. It answers whatever it is told to answer, in order, so a test can spell out
 * the exact conversation it wants. The full behavioural machine model is in the simulator package
 * and the end to end tests use that instead.
 */
public final class ScriptedPrinter implements Responder {

    /** A loopback link wired to a scripted printer. */
    public record Link(LoopbackTransport transport, ScriptedPrinter printer) {}

    private final Map<String, String> replies = new HashMap<>();
    private final List<String> seen = new ArrayList<>();
    private final List<String> queued = new ArrayList<>();
    private final Set<String> silentVerbs = new HashSet<>();
    private volatile String defaultReply;
    private volatile Duration latency;

    /** A printer answering {@code 0:} to anything unscripted. */
    public ScriptedPrinter() {
        this(Map.of());
    }

    /** A printer with these replies, answering {@code 0:} to anything else. */
    public ScriptedPrinter(Map<String, String> replies) {
        this(replies, "0:", Duration.ZERO);
    }

    /** A printer with these replies, a default ({@code null} for silence) and a delay per reply. */
    public ScriptedPrinter(Map<String, String> replies, String defaultReply, Duration latency) {
        this.replies.putAll(replies);
        this.defaultReply = defaultReply;
        this.latency = latency;
    }

    /** A loopback link named {@code bench} to a printer with these replies. */
    public static Link scriptedLink(Map<String, String> replies) {
        return link(new ScriptedPrinter(replies));
    }

    /** A loopback link named {@code bench} to a printer that answers {@code 0:} to everything. */
    public static Link scriptedLink() {
        return scriptedLink(Map.of());
    }

    /** A loopback link named {@code bench} to {@code printer}. */
    public static Link link(ScriptedPrinter printer) {
        return new Link(new LoopbackTransport(printer, "bench"), printer);
    }

    /** Answer {@code verb} with {@code reply} from now on. */
    public synchronized void expect(String verb, String reply) {
        replies.put(verb, reply);
    }

    /** Answer the next commands with these, in order, whatever they are. */
    public synchronized void queue(String... next) {
        queued.addAll(Arrays.asList(next));
    }

    /** Never answer these verbs, the way a wedged board behaves. */
    public synchronized void goSilent(String... verbs) {
        silentVerbs.addAll(Arrays.asList(verbs));
    }

    /** Answer the silenced verbs again. */
    public synchronized void speakAgain() {
        silentVerbs.clear();
    }

    /** Wait this long before every reply. */
    public void latency(Duration value) {
        latency = value;
    }

    /** What to answer unscripted verbs with, {@code null} for silence. */
    public void defaultReply(String value) {
        defaultReply = value;
    }

    /** How many times {@code verb} has been sent. */
    public synchronized int count(String verb) {
        return (int)
                seen.stream().filter(line -> line.split(":", 2)[0].equals(verb)).count();
    }

    /** Every command text received, checksum removed, in order. */
    public synchronized List<String> seen() {
        return List.copyOf(seen);
    }

    @Override
    public byte[] respond(byte[] data) throws InterruptedException {
        String text = new String(Framing.stripChecksum(strip(data)), StandardCharsets.US_ASCII);
        Duration wait = latency;
        synchronized (this) {
            seen.add(text);
        }
        if (!wait.isZero()) {
            Thread.sleep(wait);
        }
        String verb = text.split(":", 2)[0];
        String reply;
        synchronized (this) {
            if (silentVerbs.contains(verb)) {
                return new byte[0];
            }
            if (!queued.isEmpty()) {
                return Framing.encodeFrame(queued.remove(0));
            }
            reply = replies.getOrDefault(verb, defaultReply);
        }
        return reply == null ? new byte[0] : Framing.encodeFrame(reply);
    }

    private static byte[] strip(byte[] data) {
        int start = 0;
        int end = data.length;
        while (start < end && Character.isWhitespace(data[start])) {
            start++;
        }
        while (end > start && Character.isWhitespace(data[end - 1])) {
            end--;
        }
        return Arrays.copyOfRange(data, start, end);
    }
}
