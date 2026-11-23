package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.InkFamily;
import io.github.rkaushikdev.nozzlewright.storage.CartridgeFitting;
import io.github.rkaushikdev.nozzlewright.storage.CartridgeRepo;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Which cartridges come out over the next few days, and what to have on the shelf for them.
 *
 * <p>The store person's question is never the level of one pen. It is how many of each part to order
 * on Monday so that nothing stops on Thursday. A cartridge comes out for one of two reasons, whichever
 * comes first: it runs dry at the rate it has been falling since it was fitted, or its ink family's
 * service life runs out, which counts from the first time that serial was fitted in any machine,
 * because moving a half used cartridge to another line does not reset how long the ink has been open.
 *
 * <p>A cartridge whose level has not moved has no rate, and is only due when its service life says
 * so. One whose ink family the machine never reported has no service life, and is only due when it
 * runs dry. One with neither is left out rather than guessed at.
 */
public final class CartridgeForecast {

    /** A level read over less than this tells nothing about the rate it falls at. */
    static final Duration SHORTEST_BASIS = Duration.ofHours(1);

    private CartridgeForecast() {}

    /** Every fitted cartridge due out on or before {@code horizonDays} from {@code now}, soonest first. */
    public static List<CartridgeDue> due(UnitOfWork unit, Instant now, int horizonDays) {
        CartridgeRepo repo = new CartridgeRepo(unit);
        Map<Integer, String> printerIds = new PrinterRepo(unit).idsByKey();
        LocalDate last = day(now).plusDays(horizonDays);
        List<CartridgeDue> found = new ArrayList<>();
        for (CartridgeFitting row : repo.allOpen()) {
            Instant firstFitted = repo.history(row.getSerial()).stream()
                    .map(CartridgeFitting::getFittedAt)
                    .min(Comparator.naturalOrder())
                    .orElse(row.getFittedAt());
            forecast(printerIds.getOrDefault(row.getPrinterPk(), "?"), row, firstFitted, now)
                    .filter(item -> !item.dueOn().isAfter(last))
                    .ifPresent(found::add);
        }
        found.sort(Comparator.comparing(CartridgeDue::dueOn)
                .thenComparing(CartridgeDue::printerId)
                .thenComparingInt(CartridgeDue::pen));
        return List.copyOf(found);
    }

    /**
     * When one fitted cartridge comes out, if anything says.
     *
     * @param firstFitted the first time this serial went into any machine
     */
    public static Optional<CartridgeDue> forecast(
            String printerId, CartridgeFitting row, Instant firstFitted, Instant now) {
        Optional<LocalDate> empty = emptyOn(row, now);
        Optional<LocalDate> service = serviceEndsOn(row.getInkFamily(), firstFitted);
        if (empty.isEmpty() && service.isEmpty()) {
            return Optional.empty();
        }
        boolean serviceFirst =
                service.isPresent() && (empty.isEmpty() || !service.get().isAfter(empty.get()));
        return Optional.of(new CartridgeDue(
                printerId,
                row.getPen(),
                row.getSerial(),
                row.getPartNumber(),
                row.getLastLevel(),
                serviceFirst ? service.get() : empty.get(),
                serviceFirst ? CartridgeDue.Reason.SERVICE_LIFE : CartridgeDue.Reason.EMPTY));
    }

    /** How many of each part the forecast takes off the shelf, by part number. */
    public static Map<String, Long> toOrder(List<CartridgeDue> due) {
        Map<String, Long> parts = new TreeMap<>();
        due.forEach(item -> parts.merge(item.partNumber().isEmpty() ? "-" : item.partNumber(), 1L, Long::sum));
        return parts;
    }

    /** The day the level reaches nought at the rate it has fallen since fitting. */
    static Optional<LocalDate> emptyOn(CartridgeFitting row, Instant now) {
        if (row.getLastLevel() <= 0) {
            return Optional.of(day(now));
        }
        Duration basis = Duration.between(row.getFittedAt(), now);
        if (row.usedPoints() == 0 || basis.compareTo(SHORTEST_BASIS) < 0) {
            return Optional.empty();
        }
        double pointsPerSecond = (double) row.usedPoints() / basis.toSeconds();
        long secondsLeft = (long) Math.ceil(row.getLastLevel() / pointsPerSecond);
        return Optional.of(day(now.plusSeconds(secondsLeft)));
    }

    /**
     * The last day the cartridge in {@code row} may stay in service, counted from the first time its
     * serial was fitted anywhere, when its ink family is known.
     */
    public static Optional<LocalDate> serviceEndsOn(CartridgeRepo repo, CartridgeFitting row) {
        Instant firstFitted = repo.history(row.getSerial()).stream()
                .map(CartridgeFitting::getFittedAt)
                .min(Comparator.naturalOrder())
                .orElse(row.getFittedAt());
        return serviceEndsOn(row.getInkFamily(), firstFitted);
    }

    /** The last day the ink family allows, counted from the first fitting, when the family is known. */
    static Optional<LocalDate> serviceEndsOn(String inkFamily, Instant firstFitted) {
        try {
            return Optional.of(InkFamily.parse(inkFamily).serviceEndsOn(day(firstFitted)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static LocalDate day(Instant moment) {
        return LocalDate.ofInstant(moment, ZoneOffset.UTC);
    }
}
