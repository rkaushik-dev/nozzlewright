package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.doctor.Report;
import java.util.List;

/**
 * Whether this box is fit to run a site.
 *
 * @param grade the worst thing found
 * @param ok true when nothing at all needs doing
 * @param healthy true when nothing is actually broken
 * @param stateDir the directory the checks looked at
 * @param checks one row per check
 */
public record DoctorOut(String grade, boolean ok, boolean healthy, String stateDir, List<CheckOut> checks) {

    /** Copies the rows. */
    public DoctorOut {
        checks = List.copyOf(checks);
    }

    /** Built from a doctor report. */
    public static DoctorOut from(Report report) {
        return new DoctorOut(
                report.grade().value(),
                report.ok(),
                report.healthy(),
                report.stateDir().toString(),
                report.checks().stream().map(CheckOut::from).toList());
    }
}
