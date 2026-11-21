package io.github.rkaushikdev.nozzlewright.simulator;

/**
 * One entry in the simulated machine's user table.
 *
 * <p>The level may be changed while the machine is running, the way a supervisor edits a user on
 * the machine display, and the next command sees the new level.
 */
public final class SimulatedUser {

    private final String name;
    private final String pin;
    private volatile int level;

    /** A user who logs in with {@code pin} at operator {@code level}, 0 operator to 3 factory. */
    public SimulatedUser(String name, String pin, int level) {
        this.name = name;
        this.pin = pin;
        this.level = level;
    }

    /** The user name. */
    public String name() {
        return name;
    }

    /** The PIN the user logs in with. */
    public String pin() {
        return pin;
    }

    /** The operator level. */
    public int level() {
        return level;
    }

    /** Change the operator level. */
    public void level(int value) {
        level = value;
    }

    @Override
    public String toString() {
        return "SimulatedUser[name=" + name + ", level=" + level + "]";
    }
}
