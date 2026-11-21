package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.doctor.Check;

/**
 * One thing the doctor looked at.
 *
 * @param name what was looked at
 * @param grade {@code ok}, {@code warn} or {@code fail}
 * @param detail what was found
 * @param ok true when nothing needs doing
 */
public record CheckOut(String name, String grade, String detail, boolean ok) {

    /** Built from one doctor finding. */
    public static CheckOut from(Check check) {
        return new CheckOut(check.name(), check.grade().value(), check.detail(), check.ok());
    }
}
