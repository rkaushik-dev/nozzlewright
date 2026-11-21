package io.github.rkaushikdev.nozzlewright.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * A validated command ready to hand to the transport.
 *
 * @param verb the verb
 * @param args the arguments as their wire text, already validated
 * @param spec the catalogue entry the arguments were checked against
 */
public record Request(String verb, List<String> args, CommandSpec spec) {

    /** Copies the arguments. */
    public Request {
        args = List.copyOf(args);
    }

    /** The exact text that goes on the wire, terminator excluded. */
    public String render() {
        if (args.isEmpty()) {
            return verb;
        }
        return verb + ":" + String.join(Codec.ARG_SEPARATOR, args);
    }

    /** The same text with secret arguments blanked, for logs. */
    public String redacted() {
        if (args.isEmpty()) {
            return verb;
        }
        List<String> shown = new ArrayList<>(args.size());
        for (int index = 0; index < args.size(); index++) {
            if (index < spec.args().size()
                    && spec.secretArgs().contains(spec.args().get(index).name())) {
                shown.add("****");
            } else {
                shown.add(args.get(index));
            }
        }
        return verb + ":" + String.join(Codec.ARG_SEPARATOR, shown);
    }
}
