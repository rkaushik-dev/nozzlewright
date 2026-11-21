package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.api.KeyRing;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code nozzlewright key}: make and hash API keys. */
@Command(
        name = "key",
        description = "Make and hash API keys.",
        subcommands = {KeyCommands.New.class, KeyCommands.Hash.class, KeyCommands.Check.class})
final class KeyCommands extends Group {

    /** Make a key and print it with the line to put in the site file. */
    @Command(
            name = "new",
            description = {
                "Make a key and print it with the line to put in the site file.",
                "",
                "The key is printed once and never stored. Losing it means making another one, which"
                        + " takes a second, and that is the whole point of storing only the digest."
            })
    static final class New extends Leaf {

        @Option(names = "--count", defaultValue = "1", description = "How many keys to make.")
        private int count;

        @Override
        public Integer call() {
            range("--count", count, 1, 20);
            for (int made = 0; made < count; made++) {
                String key = KeyRing.newKey();
                out().println("key    " + key);
                out().println("store  " + KeyRing.hashKey(key));
            }
            return Main.OK;
        }
    }

    /** Turn a key somebody is already using into its stored form. */
    @Command(name = "hash", description = "Turn a key somebody is already using into its stored form.")
    static final class Hash extends Leaf {

        @Parameters(index = "0", paramLabel = "KEY", description = "A key somebody already has.")
        private String key;

        @Override
        public Integer call() {
            out().println(KeyRing.hashKey(key));
            return Main.OK;
        }
    }

    /** Say whether a key would be accepted by this site. */
    @Command(name = "check", description = "Say whether a key would be accepted by this site.")
    static final class Check extends Leaf {

        @Parameters(index = "0", paramLabel = "KEY", description = "A key to try against the site file.")
        private String key;

        @Override
        public Integer call() {
            KeyRing ring = KeyRing.of(context().site().apiKeys());
            if (!ring.any()) {
                out().println("this site has no keys, the API is open");
                return Main.OK;
            }
            if (!ring.accepts(key)) {
                throw Output.fail("that key is not on the ring, " + ring.advice());
            }
            out().println("accepted, " + ring.advice());
            return Main.OK;
        }
    }
}
