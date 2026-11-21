package io.github.rkaushikdev.nozzlewright.printer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.settings.SessionSettings;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import java.util.ArrayList;
import java.util.List;

/** One simulated machine, reachable through a real session that is up and logged in. */
final class SimulatedBench {

    final SimulatedPrinter machine;
    final PrinterSession session;

    private SimulatedBench(SimulatedPrinter machine, PrinterSession session) {
        this.machine = machine;
        this.session = session;
    }

    /** Bring a session up on {@code machine} as {@code user}, waiting up to three seconds for it. */
    static SimulatedBench start(String printerId, SimulatedPrinter machine, Credentials user, SessionSettings settings)
            throws InterruptedException {
        PrinterSession session = PrinterSession.builder(printerId, () -> new LoopbackTransport(machine, "sim"))
                .credentials(user)
                .linkSettings(Fast.LINK)
                .sessionSettings(settings)
                .build();
        session.start();
        boolean ready = session.waitReady(3.0);
        if (!ready) {
            session.stop();
        }
        assertThat(ready).as("session for %s came up", printerId).isTrue();
        return new SimulatedBench(machine, session);
    }

    /** As the factory user, with the quick session timings. */
    static SimulatedBench start(String printerId, SimulatedPrinter machine) throws InterruptedException {
        return start(printerId, machine, new Credentials("factory", "9999"), Fast.SESSION);
    }

    void stop() throws InterruptedException {
        session.stop();
    }

    /** True when some command the machine received starts with {@code prefix}. */
    boolean sawCommandStarting(String prefix) {
        List<String> seen = machine.seen();
        return seen.stream().anyMatch(line -> line.startsWith(prefix));
    }

    /** A one group head of pens one to {@code pens}, each with a standard firing profile, at fixed speed. */
    static PrinterConfig fixedSpeedConfig(int pens) {
        List<PenGeometry> geometry = new ArrayList<>();
        List<FiringProfile> profiles = new ArrayList<>();
        for (int pen = 1; pen <= pens; pen++) {
            geometry.add(new PenGeometry(pen));
            profiles.add(new FiringProfile(pen, 9000, 1500));
        }
        HeadGeometry head =
                new HeadGeometry("HVX8PEN", 300, Head.NATIVE_VERTICAL_DPI, List.of(new GroupGeometry(1, geometry)));
        return PrinterConfig.builder(head, new FiringTable(profiles))
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }
}
