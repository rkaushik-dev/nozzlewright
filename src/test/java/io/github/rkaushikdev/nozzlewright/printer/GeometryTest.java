package io.github.rkaushikdev.nozzlewright.printer;

import static io.github.rkaushikdev.nozzlewright.printer.Head.NOZZLES_PER_ROW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GeometryTest {

    static GroupGeometry stackedGroup(int pens, int spacing) {
        List<PenGeometry> made = new ArrayList<>();
        for (int n = 0; n < pens; n++) {
            made.add(new PenGeometry(n + 1).withVerticalOffsetDots(n * 2 * NOZZLES_PER_ROW));
        }
        return new GroupGeometry(1, made).withSpacingDots(spacing);
    }

    static GroupGeometry stackedGroup() {
        return stackedGroup(2, 0);
    }

    static HeadGeometry head() {
        return new HeadGeometry("HVX8PEN", List.of(stackedGroup()));
    }

    @Test
    void orientationQuarterTurns() {
        assertThat(Orientation.UP.quarterTurns()).isZero();
        assertThat(Orientation.RIGHT.quarterTurns()).isEqualTo(1);
        assertThat(Orientation.DOWN.quarterTurns()).isEqualTo(2);
        assertThat(Orientation.LEFT.quarterTurns()).isEqualTo(3);
    }

    @Test
    void orientationAxisSwap() {
        assertThat(Orientation.UP.swapsAxes()).isFalse();
        assertThat(Orientation.RIGHT.swapsAxes()).isTrue();
        assertThat(Orientation.DOWN.swapsAxes()).isFalse();
        assertThat(Orientation.LEFT.swapsAxes()).isTrue();
    }

    @Test
    void penHeightFollowsTheRowCount() {
        assertThat(new PenGeometry(1).withRows(2).heightDots()).isEqualTo(104);
        assertThat(new PenGeometry(1).withRows(1).heightDots()).isEqualTo(52);
        assertThat(new PenGeometry(1).withRows(4).heightDots()).isEqualTo(208);
    }

    @Test
    void penSpan() {
        PenGeometry pen = new PenGeometry(1, 2, 10, 0);
        assertThat(pen.firstRow()).isEqualTo(10);
        assertThat(pen.lastRow()).isEqualTo(113);
        assertThat(pen.covers(10)).isTrue();
        assertThat(pen.covers(113)).isTrue();
        assertThat(pen.covers(9)).isFalse();
        assertThat(pen.covers(114)).isFalse();
    }

    @Test
    void penIndexBounds() {
        new PenGeometry(1).validate();
        new PenGeometry(8).validate();
        assertThatThrownBy(() -> new PenGeometry(9).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("one to eight");
        assertThatThrownBy(() -> new PenGeometry(0).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("one to eight");
    }

    @Test
    void penRowBounds() {
        assertThatThrownBy(() -> new PenGeometry(1).withRows(5).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("one to four rows");
    }

    @Test
    void penOffsetBounds() {
        assertThatThrownBy(
                        () -> new PenGeometry(1).withHorizontalOffsetDots(3000).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("2048 dots");
        assertThatThrownBy(() -> new PenGeometry(1).withVerticalOffsetDots(600).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("512 dots");
    }

    @Test
    void groupHeightStacksThePens() {
        assertThat(stackedGroup(2, 0).heightDots()).isEqualTo(208);
        assertThat(stackedGroup(1, 0).heightDots()).isEqualTo(104);
    }

    @Test
    void groupSpacingAddsHeight() {
        assertThat(stackedGroup(2, 6).heightDots()).isEqualTo(214);
    }

    @Test
    void groupLookupByPenIndex() {
        GroupGeometry group = stackedGroup();
        assertThat(group.pen(2).index()).isEqualTo(2);
        assertThatThrownBy(() -> group.pen(7)).isInstanceOf(ConfigError.class).hasMessageContaining("no pen 7");
    }

    @Test
    void rowOwner() {
        GroupGeometry group = stackedGroup();
        assertThat(group.rowOwner(0)).isPresent();
        assertThat(group.rowOwner(0).get().index()).isEqualTo(1);
        assertThat(group.rowOwner(150).get().index()).isEqualTo(2);
        assertThat(group.rowOwner(9999)).isEmpty();
    }

    @Test
    void groupRejectsADuplicatePen() {
        GroupGeometry group = new GroupGeometry(1, List.of(new PenGeometry(1), new PenGeometry(1)));
        assertThatThrownBy(group::validate).isInstanceOf(ConfigError.class).hasMessageContaining("twice in one group");
    }

    @Test
    void groupRejectsNoPens() {
        assertThatThrownBy(() -> new GroupGeometry(1, List.of()).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least one pen");
    }

    @Test
    void groupIndexBounds() {
        assertThatThrownBy(() -> new GroupGeometry(5, List.of(new PenGeometry(1))).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("one to four");
    }

    @Test
    void groupRejectsNegativeOffsets() {
        assertThatThrownBy(() -> new GroupGeometry(1, List.of(new PenGeometry(1)))
                        .withOffsetDots(-1)
                        .validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be negative");
        assertThatThrownBy(() -> new GroupGeometry(1, List.of(new PenGeometry(1)))
                        .withSpacingDots(-4)
                        .validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void headCountsPensAcrossGroups() {
        HeadGeometry machine = new HeadGeometry(
                "HVX8PEN",
                List.of(
                        new GroupGeometry(1, List.of(new PenGeometry(1), new PenGeometry(2))),
                        new GroupGeometry(2, List.of(new PenGeometry(3)))));
        machine.validate();
        assertThat(machine.penCount()).isEqualTo(3);
        assertThat(machine.group(2).penCount()).isEqualTo(1);
    }

    @Test
    void headRejectsAPenClaimedTwice() {
        HeadGeometry machine = new HeadGeometry(
                "HVX8PEN",
                List.of(
                        new GroupGeometry(1, List.of(new PenGeometry(1))),
                        new GroupGeometry(2, List.of(new PenGeometry(1)))));
        assertThatThrownBy(machine::validate)
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("claimed by two groups");
    }

    @Test
    void headRejectsADuplicateGroup() {
        HeadGeometry machine = new HeadGeometry(
                "HVX8PEN",
                List.of(
                        new GroupGeometry(1, List.of(new PenGeometry(1))),
                        new GroupGeometry(1, List.of(new PenGeometry(2)))));
        assertThatThrownBy(machine::validate)
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("group appears twice");
    }

    @Test
    void headRejectsAnUnsupportedFiringRate() {
        assertThatThrownBy(() -> head().withHorizontalDpi(200).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("the board fires at");
    }

    @Test
    void headRefusesToChangeTheNozzlePitch() {
        assertThatThrownBy(() -> head().withVerticalDpi(600).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("fixed at 300");
    }

    @Test
    void headNeedsAGroup() {
        assertThatThrownBy(() -> new HeadGeometry("HVXDOD", List.of()).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least one group");
    }

    @Test
    void unknownGroupLookup() {
        assertThatThrownBy(() -> head().group(3))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no group 3");
    }

    @ParameterizedTest
    @CsvSource({"300, 300, 25.4", "150, 150, 25.4", "600, 300, 12.7", "75, 75, 25.4"})
    void dotsToMm(int dpi, int dots, double mm) {
        assertThat(head().withHorizontalDpi(dpi).dotsToMm(dots)).isCloseTo(mm, within(1e-9));
    }

    @Test
    void mmToDotsRoundsToNearest() {
        HeadGeometry machine = head().withHorizontalDpi(300);
        assertThat(machine.mmToDots(25.4)).isEqualTo(300);
        assertThat(machine.mmToDots(0.0847)).isEqualTo(1);
        assertThat(machine.mmToDots(0.0)).isZero();
    }

    @Test
    void mmToDotsHandlesNegatives() {
        HeadGeometry machine = head().withHorizontalDpi(300);
        assertThat(machine.mmToDots(-25.4)).isEqualTo(-300);
        assertThat(machine.mmToDots(-0.0847)).isEqualTo(-1);
    }

    @Test
    void verticalAxisUsesTheNozzlePitch() {
        HeadGeometry machine = head().withHorizontalDpi(150);
        assertThat(machine.dotsToMm(300, Axis.Y)).isCloseTo(25.4, within(1e-9));
        assertThat(machine.dotsToMm(300, Axis.X)).isCloseTo(50.8, within(1e-9));
    }

    @Test
    void axisMustBeXOrY() {
        assertThatThrownBy(() -> head().dotsToMm(1, Axis.parse("z")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("axis is x or y");
    }

    @Test
    void columnPitch() {
        assertThat(head().withHorizontalDpi(300).columnPitchMm()).isCloseTo(25.4 / 300, within(1e-12));
        assertThat(head().withHorizontalDpi(600).columnPitchMm()).isCloseTo(25.4 / 600, within(1e-12));
    }

    @Test
    void headHeightIsTheTallestGroup() {
        HeadGeometry machine = new HeadGeometry(
                "HVX8PEN",
                List.of(
                        new GroupGeometry(1, List.of(new PenGeometry(1))),
                        new GroupGeometry(
                                2, List.of(new PenGeometry(2), new PenGeometry(3).withVerticalOffsetDots(104)))));
        assertThat(machine.heightDots()).isEqualTo(208);
    }
}
