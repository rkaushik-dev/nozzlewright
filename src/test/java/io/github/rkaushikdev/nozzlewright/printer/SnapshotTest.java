package io.github.rkaushikdev.nozzlewright.printer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import io.github.rkaushikdev.nozzlewright.protocol.Dialect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotTest {

    static PrinterConfig sample() {
        HeadGeometry head = new HeadGeometry(
                "HVX8PEN",
                List.of(
                        new GroupGeometry(
                                1,
                                List.of(new PenGeometry(1), new PenGeometry(2).withVerticalOffsetDots(104)),
                                Orientation.DOWN,
                                true,
                                48,
                                2),
                        new GroupGeometry(2, List.of(new PenGeometry(3).withRows(4)))));
        FiringTable firing = FiringTable.of(
                new FiringProfile(1, 9000, 1500, 0, 29_500),
                new FiringProfile(2, 9400, 1450, 300),
                new FiringProfile(3, 9100, 1600));
        return PrinterConfig.builder(head, firing)
                .photocell(new Photocell(2, PhotocellMode.GATED, 64, 120))
                .encoder(new ShaftEncoder(2000, 200, true))
                .autospeedMmPerMin(0)
                .printMode(PrintMode.REPEAT)
                .fieldbus(Fieldbus.MODBUS)
                .repeatCount(3)
                .repeatGapMm(25)
                .highFire(true)
                .build();
    }

    /** The rows CONFIG would send for {@code config}, each one a map the test may change. */
    static List<Map<String, String>> rowsFor(PrinterConfig config) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, String> row : Codec.parseTable(Snapshot.renderConfigTable(config))) {
            rows.add(new LinkedHashMap<>(row));
        }
        return rows;
    }

    static List<Map<String, String>> without(String section) {
        return rowsFor(sample()).stream()
                .filter(row -> !section.equals(row.get("SEC")))
                .toList();
    }

    static Map<String, String> first(List<Map<String, String>> rows, String section) {
        return rows.stream()
                .filter(row -> section.equals(row.get("SEC")))
                .findFirst()
                .orElseThrow();
    }

    static PrinterConfig parse(List<Map<String, String>> rows) {
        return Snapshot.parseConfigTable(rows, "HVX8PEN");
    }

    @Test
    void configVerbIsRevisionTwo() {
        assertThat(Dialect.REVISION_TWO.supports("CONFIG")).isTrue();
        assertThat(Dialect.REVISION_ONE.supports("CONFIG")).isFalse();
    }

    @Test
    void roundTripIsLossless() {
        PrinterConfig original = sample();
        PrinterConfig rebuilt = parse(rowsFor(original));
        assertThat(rebuilt).isEqualTo(original);
    }

    @Test
    void roundTripSurvivesASecondPass() {
        PrinterConfig once = parse(rowsFor(sample()));
        PrinterConfig twice = parse(rowsFor(once));
        assertThat(once).isEqualTo(twice);
    }

    @Test
    void rebuiltConfigStillValidates() {
        parse(rowsFor(sample())).validate();
    }

    @Test
    void rowOrderDoesNotMatter() {
        List<Map<String, String>> shuffled = new ArrayList<>(rowsFor(sample()));
        Collections.reverse(shuffled);
        assertThat(parse(shuffled)).isEqualTo(sample());
    }

    @Test
    void missingEncoderSectionIsAllowed() {
        PrinterConfig rebuilt = parse(without("ENC"));
        assertThat(rebuilt.encoder()).isEmpty();
    }

    @Test
    void missingPhotocellSectionFallsBackToTheDefault() {
        assertThat(parse(without("CELL")).photocell()).isEqualTo(new Photocell());
    }

    @Test
    void missingHeadRowIsAnError() {
        assertThatThrownBy(() -> parse(without("HEAD")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no HEAD row");
    }

    @Test
    void missingGroupRowsIsAnError() {
        assertThatThrownBy(() -> parse(without("GROUP")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no GROUP rows");
    }

    @Test
    void missingFiringRowsIsAnError() {
        assertThatThrownBy(() -> parse(without("FIRING")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no FIRING rows");
    }

    @Test
    void rowWithoutASectionIsAnError() {
        assertThatThrownBy(() -> parse(List.of(Map.of("HDPI", "300"))))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no SEC field");
    }

    @Test
    void unknownSectionIsAnError() {
        List<Map<String, String>> rows = new ArrayList<>(rowsFor(sample()));
        rows.add(Map.of("SEC", "PLASMA", "X", "1"));
        assertThatThrownBy(() -> parse(rows))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("section 'PLASMA'");
    }

    @Test
    void missingFieldNamesTheSection() {
        List<Map<String, String>> rows = rowsFor(sample());
        for (Map<String, String> row : rows) {
            if ("HEAD".equals(row.get("SEC"))) {
                row.remove("HDPI");
            }
        }
        assertThatThrownBy(() -> parse(rows)).isInstanceOf(ProtocolError.class).hasMessageContaining("HEAD");
    }

    @Test
    void nonNumericFieldIsAnError() {
        List<Map<String, String>> rows = rowsFor(sample());
        first(rows, "FIRING").put("MV", "high");
        assertThatThrownBy(() -> parse(rows)).isInstanceOf(ProtocolError.class).hasMessageContaining("is not a number");
    }

    @Test
    void groupNamingAMissingPenIsAnError() {
        List<Map<String, String>> rows = rowsFor(sample()).stream()
                .filter(row -> !("PEN".equals(row.get("SEC")) && "2".equals(row.get("IDX"))))
                .toList();
        assertThatThrownBy(() -> parse(rows))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no PEN row follows");
    }

    @Test
    void groupWithAnEmptyPenListIsAnError() {
        List<Map<String, String>> rows = rowsFor(sample());
        first(rows, "GROUP").put("PENS", " ");
        assertThatThrownBy(() -> parse(rows)).isInstanceOf(ProtocolError.class).hasMessageContaining("no PENS field");
    }

    @Test
    void nonNumericPenListIsAnError() {
        List<Map<String, String>> rows = rowsFor(sample());
        first(rows, "GROUP").put("PENS", "one|two");
        assertThatThrownBy(() -> parse(rows)).isInstanceOf(ProtocolError.class).hasMessageContaining("not numeric");
    }

    @Test
    void unknownOrientationIsAnError() {
        List<Map<String, String>> rows = rowsFor(sample());
        first(rows, "GROUP").put("ORIEN", "SIDEWAYS");
        assertThatThrownBy(() -> parse(rows)).isInstanceOf(ProtocolError.class).hasMessageContaining("orientation");
    }

    @Test
    void unknownPhotocellModeIsAnError() {
        List<Map<String, String>> rows = rowsFor(sample());
        first(rows, "CELL").put("MODE", "PSYCHIC");
        assertThatThrownBy(() -> parse(rows)).isInstanceOf(ProtocolError.class).hasMessageContaining("photocell mode");
    }

    @Test
    void unknownPrintModeIsAnError() {
        List<Map<String, String>> rows = rowsFor(sample());
        first(rows, "MODE").put("MODE", "TURBO");
        assertThatThrownBy(() -> parse(rows)).isInstanceOf(ProtocolError.class).hasMessageContaining("print mode");
    }

    @Test
    void unknownFieldbusIsAnError() {
        List<Map<String, String>> rows = rowsFor(sample());
        first(rows, "MODE").put("BUS", "CANOPEN");
        assertThatThrownBy(() -> parse(rows)).isInstanceOf(ProtocolError.class).hasMessageContaining("fieldbus");
    }

    @Test
    void aSnapshotOfTwoMachinesDiffsCleanly() {
        PrinterConfig first = parse(rowsFor(sample()));
        String louder = Snapshot.renderConfigTable(sample()).replace("MV=9000", "MV=10500");
        PrinterConfig second = parse(Codec.parseTable(louder));
        ConfigDiff diff = first.diff(second);
        assertThat(diff.commands()).containsExactly(new ConfigDiff.Command("FIRINGVOL", List.of(1, 10500)));
    }
}
