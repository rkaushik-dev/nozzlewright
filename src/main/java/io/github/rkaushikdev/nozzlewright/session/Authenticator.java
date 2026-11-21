package io.github.rkaushikdev.nozzlewright.session;

import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.NotAuthenticated;
import io.github.rkaushikdev.nozzlewright.PermissionDenied;
import io.github.rkaushikdev.nozzlewright.protocol.Catalogue;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import io.github.rkaushikdev.nozzlewright.protocol.Status;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Opens, holds and closes the operator session on one printer.
 *
 * <p>The machine keeps its own user table and its own idea of who is logged in. It is mirrored here
 * so the handheld can grey out a button rather than send a command it knows will come back with
 * status 4. A re-login is only ever attempted for the operator who was already logged in, never as
 * a way to escalate.
 */
public final class Authenticator {

    private static final Set<Integer> GIVE_UP =
            Set.of(Status.NOT_FOUND.code(), Status.NO_PERMISSION.code(), Status.BAD_PARAM.code());

    private final Channel channel;
    private volatile Operator operator;
    private volatile Credentials credentials;
    private volatile int relogins;

    /** An authenticator for the session on {@code channel}. */
    public Authenticator(Channel channel) {
        this.channel = channel;
    }

    /** The logged in operator, if anyone is. */
    public Optional<Operator> operator() {
        return Optional.ofNullable(operator);
    }

    /** True while a session is open. */
    public boolean loggedIn() {
        return operator != null;
    }

    /** How many times the operator was logged back in after a reconnect. */
    public int relogins() {
        return relogins;
    }

    /**
     * Refuse unless the current operator may send {@code verb}.
     *
     * @throws NotAuthenticated when nobody is logged in
     * @throws PermissionDenied when the operator's level is too low
     */
    public void require(String verb) {
        Operator current = operator;
        if (current == null) {
            throw new NotAuthenticated("log in before sending " + verb);
        }
        int needed = Catalogue.lookup(verb).level();
        if (current.level() < needed) {
            throw new PermissionDenied(verb, current.level(), needed);
        }
    }

    /** Open a session and remember who it belongs to. */
    public Operator login(String name, String pin) throws InterruptedException {
        Response response = channel.send("LOGIN", name, pin);
        Map<String, String> record = response.record();
        Operator made = new Operator(record.getOrDefault("USER", name), readLevel(record));
        operator = made;
        credentials = new Credentials(name, pin);
        return made;
    }

    /** Close the session and forget the credentials, even when the machine refuses the logout. */
    public void logout() throws InterruptedException {
        if (operator == null) {
            return;
        }
        try {
            channel.send("LOGOUT");
        } finally {
            operator = null;
            credentials = null;
        }
    }

    /**
     * Drop the cached state without talking to the machine.
     *
     * <p>Called when the link went away. The printer will have timed the session out on its own, so
     * there is nothing to log out of.
     */
    public void forget() {
        operator = null;
        credentials = null;
    }

    /**
     * Note that the printer timed the session out while the credentials are still held, as it does
     * when the radio drops.
     */
    void sessionTimedOut() {
        operator = null;
    }

    /**
     * Log the same operator back in after a reconnect.
     *
     * <p>Empty when there is nothing to resume. Never guesses at a different operator, and gives up
     * quietly on a refusal that means the user or the PIN is gone, because a reconnect loop must not
     * turn a wrong PIN into a lockout.
     *
     * @throws CommandRejected when the machine refuses for any other reason
     */
    public Optional<Operator> resume() throws InterruptedException {
        Credentials held = credentials;
        if (held == null) {
            return Optional.empty();
        }
        Operator resumed;
        try {
            resumed = login(held.name(), held.pin());
        } catch (CommandRejected error) {
            if (GIVE_UP.contains(error.status())) {
                forget();
                return Optional.empty();
            }
            throw error;
        }
        relogins++;
        return Optional.of(resumed);
    }

    /** The operator level out of a LOGIN reply: missing or junk means 0, and 3 is the ceiling. */
    private static int readLevel(Map<String, String> record) {
        String raw = record.getOrDefault("LEVEL", "").strip();
        if (raw.isEmpty()) {
            return 0;
        }
        long level;
        try {
            level = Numbers.parseLong(raw);
        } catch (NumberFormatException error) {
            return 0;
        }
        return (int) Math.max(0, Math.min(level, 3));
    }
}
