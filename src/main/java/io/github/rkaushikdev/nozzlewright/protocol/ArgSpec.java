package io.github.rkaushikdev.nozzlewright.protocol;

import java.util.List;
import java.util.OptionalInt;

/**
 * One positional argument of a command.
 *
 * @param name the argument name, as the catalogue and the error messages use it
 * @param kind how it is written on the wire
 * @param low the smallest whole number allowed, for {@link ArgKind#INT}
 * @param high the largest whole number allowed, for {@link ArgKind#INT}
 * @param choices the words allowed, for {@link ArgKind#CHOICE}
 * @param maxLen the longest text allowed, for {@link ArgKind#TEXT}
 */
public record ArgSpec(
        String name, ArgKind kind, OptionalInt low, OptionalInt high, List<String> choices, OptionalInt maxLen) {

    /** Copies the choices. */
    public ArgSpec {
        choices = List.copyOf(choices);
    }

    /** A whole number between {@code low} and {@code high}, both included. */
    public static ArgSpec integer(String name, int low, int high) {
        return new ArgSpec(
                name, ArgKind.INT, OptionalInt.of(low), OptionalInt.of(high), List.of(), OptionalInt.empty());
    }

    /** Text no longer than {@code maxLen} characters. */
    public static ArgSpec text(String name, int maxLen) {
        return new ArgSpec(
                name, ArgKind.TEXT, OptionalInt.empty(), OptionalInt.empty(), List.of(), OptionalInt.of(maxLen));
    }

    /** An on or off flag. */
    public static ArgSpec flag(String name) {
        return new ArgSpec(
                name, ArgKind.FLAG, OptionalInt.empty(), OptionalInt.empty(), List.of(), OptionalInt.empty());
    }

    /** One word out of a fixed list. */
    public static ArgSpec choice(String name, String... choices) {
        return new ArgSpec(
                name, ArgKind.CHOICE, OptionalInt.empty(), OptionalInt.empty(), List.of(choices), OptionalInt.empty());
    }
}
