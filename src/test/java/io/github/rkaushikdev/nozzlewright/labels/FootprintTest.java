package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.Fixtures.standardFonts;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FootprintTest {

    static final Footprint CODE = new Footprint("code", 100, 10, 200, 50, 20, 40);

    @Test
    void rectanglesThatShareADotOverlap() {
        assertThat(Footprint.of("a", 0, 0, 10, 10).overlaps(Footprint.of("b", 9, 9, 5, 5)))
                .isTrue();
        assertThat(Footprint.of("a", 0, 0, 10, 10).overlaps(Footprint.of("b", 10, 0, 5, 5)))
                .isFalse();
        assertThat(Footprint.of("a", 0, 0, 10, 10).overlaps(Footprint.of("b", 0, 10, 5, 5)))
                .isFalse();
    }

    @Test
    void anEmptyRectangleOverlapsNothing() {
        assertThat(Footprint.of("a", 0, 0, 0, 10).overlaps(Footprint.of("b", 0, 0, 5, 5)))
                .isFalse();
    }

    @Test
    void theQuietZonesSitAtEachEndOfTheBars() {
        assertThat(CODE.quietZones())
                .containsExactly(Footprint.of("code", 100, 10, 20, 40), Footprint.of("code", 280, 10, 20, 40));
    }

    @Test
    void somethingInTheLeftZoneIntrudes() {
        assertThat(CODE.intrudedBy(Footprint.of("date", 90, 20, 15, 7))).isTrue();
    }

    @Test
    void somethingInTheRightZoneIntrudes() {
        assertThat(CODE.intrudedBy(Footprint.of("date", 295, 20, 30, 7))).isTrue();
    }

    @Test
    void somethingUnderTheBarsDoesNotIntrude() {
        // Below the bars is the human readable line, which is not quiet zone.
        assertThat(CODE.intrudedBy(Footprint.of("date", 100, 52, 30, 7))).isFalse();
        assertThat(CODE.intrudedBy(Footprint.of("date", 300, 20, 30, 7))).isFalse();
    }

    @Test
    void anElementWithNoZoneIsNeverIntruded() {
        assertThat(Footprint.of("text", 0, 0, 100, 100).intrudedBy(Footprint.of("x", 0, 0, 5, 5)))
                .isFalse();
    }

    @Test
    void theRasterMeasuresEveryKindOfElement() {
        LabelTemplate template = LabelTemplate.parse("LABEL m 64\n"
                + "FIELD a TEXT LOT\n"
                + "FIELD n TEXT 4417\n"
                + "TEXT t 5,1 a font=5x7\n"
                + "BARCODE b 40,10 n height=30 module=2\n"
                + "RULE r 0,60 width=90 height=2\n"
                + "END\n");
        ResolvedLabel resolved = template.resolve(new FieldContext(LocalDateTime.of(2025, 6, 1, 8, 0)));
        List<Footprint> footprints = Raster.footprints(resolved, standardFonts());
        int textWidth = standardFonts().get("5x7").columnsFor("LOT").size();
        int captionHeight = standardFonts().get(TextElement.DEFAULT_FONT).height();
        assertThat(footprints)
                .containsExactly(
                        Footprint.of("t", 5, 1, textWidth, 7),
                        new Footprint(
                                "b",
                                40,
                                10,
                                Code128.modulesFor("4417").size() * 2,
                                30 + Raster.HUMAN_READABLE_GAP + captionHeight,
                                Code128.QUIET_ZONE_MODULES * 2,
                                30),
                        Footprint.of("r", 0, 60, 90, 2));
    }
}
