package io.github.rkaushikdev.nozzlewright.cli;

import picocli.CommandLine.Option;

/** What every command group shares: it does nothing itself and asks for one of its commands. */
abstract class Group {

    @Option(names = "--help", usageHelp = true, description = "Show this message and exit.")
    boolean help;
}
