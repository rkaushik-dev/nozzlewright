package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.doctor.Check;
import io.github.rkaushikdev.nozzlewright.doctor.Doctor;
import io.github.rkaushikdev.nozzlewright.doctor.Report;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code nozzlewright doctor}: check whether this box is fit to run a site.
 *
 * <p>Exits one when something is actually broken. A warning on its own is not a failure: a gateway
 * with no site file and no labels yet is a gateway somebody is halfway through commissioning, and it
 * should not fail a check.
 */
@Command(
        name = "doctor",
        description = {
            "Check whether this box is fit to run a site.",
            "",
            "Exits one when something is actually broken. A warning on its own is not a failure: a"
                    + " gateway with no site file and no labels yet is a gateway somebody is halfway"
                    + " through commissioning, and it should not fail a check."
        })
final class DoctorCommand extends Leaf {

    @Option(names = "--json", description = "Print JSON.")
    private boolean asJson;

    @Override
    public Integer call() {
        Context context = context();
        Report report = Doctor.runChecks(context.stateDir(), context.databaseUrl());
        if (asJson) {
            Output.emit(out(), report.checks().stream().map(Check::asMap).toList(), true);
        } else {
            out().println(report.render());
        }
        return report.healthy() ? Main.OK : Main.REFUSED;
    }
}
