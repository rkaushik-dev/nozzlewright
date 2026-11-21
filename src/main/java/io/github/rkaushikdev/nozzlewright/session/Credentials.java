package io.github.rkaushikdev.nozzlewright.session;

/**
 * A user name and PIN held for the length of a session.
 *
 * <p>The PIN is held only so an automatic re-login after a radio drop does not make the operator
 * type it again, and it never appears in the text form.
 *
 * @param name the user name
 * @param pin the PIN
 */
public record Credentials(String name, String pin) {

    @Override
    public String toString() {
        return name + "/****";
    }
}
