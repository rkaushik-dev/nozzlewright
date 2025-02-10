"""Reading a whole configuration back off a machine.

Every setting verb in the catalogue writes. Reading them back one at a time
would be forty round trips over a radio that manages maybe eight a second, so
revision 2 firmware grew one bulk verb, CONFIG, that dumps the lot as a table.

Each row carries a SEC field saying what kind of row it is:

    SEC=HEAD    the machine as a whole
    SEC=GROUP   one print group
    SEC=PEN     one cartridge position
    SEC=FIRING  one firing profile
    SEC=ENC     the shaft encoder, absent when none is fitted
    SEC=CELL    the product detect input
    SEC=MODE    print mode and everything that hangs off it

Rows may arrive in any order and older builds leave out sections they do not
have, so the parser collects first and assembles afterwards. Anything it cannot
make sense of is an error rather than a default, because a silently defaulted
firing voltage is a cooked print head.
"""

from __future__ import annotations

from nozzlewright.errors import ProtocolError
from nozzlewright.printer.config import Fieldbus, PrinterConfig, PrintMode
from nozzlewright.printer.encoder import Photocell, PhotocellMode, ShaftEncoder, SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import (
    GroupGeometry,
    HeadGeometry,
    Orientation,
    PenGeometry,
)

__all__ = ["parse_config_table", "render_config_table"]

_PEN_LIST_SEPARATOR = "|"


def _need(row: dict[str, str], key: str) -> str:
    """Pull a field out of a row or say which row was short."""
    value = row.get(key)
    if value is None or not value.strip():
        raise ProtocolError("CONFIG row %r has no %s field" % (row.get("SEC", "?"), key))
    return value.strip()


def _need_int(row: dict[str, str], key: str) -> int:
    raw = _need(row, key)
    try:
        return int(raw)
    except ValueError:
        raise ProtocolError("CONFIG field %s=%r is not a number" % (key, raw)) from None


def _flag(row: dict[str, str], key: str, default: bool = False) -> bool:
    raw = row.get(key, "").strip().lower()
    if not raw:
        return default
    return raw in ("1", "on", "yes", "true")


def parse_config_table(rows: list[dict[str, str]], *, family: str) -> PrinterConfig:
    """Assemble a :class:`PrinterConfig` from the rows CONFIG returned."""
    head_row: dict[str, str] | None = None
    mode_row: dict[str, str] | None = None
    encoder_row: dict[str, str] | None = None
    cell_row: dict[str, str] | None = None
    group_rows: list[dict[str, str]] = []
    pen_rows: dict[int, dict[str, str]] = {}
    firing_rows: list[dict[str, str]] = []

    for row in rows:
        section = row.get("SEC", "").strip().upper()
        if section == "HEAD":
            head_row = row
        elif section == "MODE":
            mode_row = row
        elif section == "ENC":
            encoder_row = row
        elif section == "CELL":
            cell_row = row
        elif section == "GROUP":
            group_rows.append(row)
        elif section == "PEN":
            pen_rows[_need_int(row, "IDX")] = row
        elif section == "FIRING":
            firing_rows.append(row)
        elif not section:
            raise ProtocolError("CONFIG row has no SEC field")
        else:
            raise ProtocolError("CONFIG row section %r is not one we know" % (section,))

    if head_row is None:
        raise ProtocolError("CONFIG reply carries no HEAD row")
    if not group_rows:
        raise ProtocolError("CONFIG reply carries no GROUP rows")
    if not firing_rows:
        raise ProtocolError("CONFIG reply carries no FIRING rows")

    # Rows arrive in whatever order the firmware walked its own tables, and a
    # machine that lists group two first is not a different machine. Sorting
    # here is what makes two snapshots comparable with ==.
    groups = tuple(sorted((_build_group(row, pen_rows) for row in group_rows), key=_by_index))
    head = HeadGeometry(
        family=family,
        horizontal_dpi=_need_int(head_row, "HDPI"),
        vertical_dpi=_need_int(head_row, "VDPI"),
        groups=groups,
    )
    firing = FiringTable(
        profiles=tuple(sorted((_build_profile(row) for row in firing_rows), key=_by_pen))
    )
    encoder = _build_encoder(encoder_row)
    photocell = _build_photocell(cell_row)
    return _build_config(head, firing, encoder, photocell, mode_row)


def _by_index(group: GroupGeometry) -> int:
    return group.index


def _by_pen(profile: FiringProfile) -> int:
    return profile.pen


def _build_group(row: dict[str, str], pen_rows: dict[int, dict[str, str]]) -> GroupGeometry:
    index = _need_int(row, "IDX")
    pen_field = _need(row, "PENS")
    pens: list[PenGeometry] = []
    for token in pen_field.split(_PEN_LIST_SEPARATOR):
        text = token.strip()
        if not text:
            continue
        try:
            pen_index = int(text)
        except ValueError:
            raise ProtocolError("CONFIG pen list %r is not numeric" % (pen_field,)) from None
        pen_row = pen_rows.get(pen_index)
        if pen_row is None:
            raise ProtocolError("group %d names pen %d and no PEN row follows" % (index, pen_index))
        pens.append(
            PenGeometry(
                index=pen_index,
                rows=_need_int(pen_row, "ROWS"),
                vertical_offset_dots=_need_int(pen_row, "VOFF"),
                horizontal_offset_dots=_need_int(pen_row, "HOFF"),
            )
        )
    if not pens:
        raise ProtocolError("group %d lists no pens" % index)

    raw_orientation = _need(row, "ORIEN").upper()
    try:
        orientation = Orientation(raw_orientation)
    except ValueError:
        raise ProtocolError("orientation %r is not one we know" % (raw_orientation,)) from None

    return GroupGeometry(
        index=index,
        pens=tuple(pens),
        orientation=orientation,
        reverse=_flag(row, "REV"),
        offset_dots=_need_int(row, "OFF"),
        spacing_dots=_need_int(row, "SPACE"),
    )


def _build_profile(row: dict[str, str]) -> FiringProfile:
    return FiringProfile(
        pen=_need_int(row, "PEN"),
        millivolts=_need_int(row, "MV"),
        pulse_ns=_need_int(row, "PULSE"),
        warm_ns=_need_int(row, "WARM"),
        resistance_mohm=_need_int(row, "ROHM"),
    )


def _build_encoder(row: dict[str, str] | None) -> ShaftEncoder | None:
    if row is None:
        return None
    return ShaftEncoder(
        pulses_per_rev=_need_int(row, "PPR"),
        wheel_mm=_need_int(row, "WHEEL"),
        reverse=_flag(row, "REV"),
    )


def _build_photocell(row: dict[str, str] | None) -> Photocell:
    if row is None:
        return Photocell()
    raw_mode = row.get("MODE", "EDGE").strip().upper()
    try:
        mode = PhotocellMode(raw_mode)
    except ValueError:
        raise ProtocolError("photocell mode %r is not one we know" % (raw_mode,)) from None
    return Photocell(
        channel=_need_int(row, "CH"),
        mode=mode,
        mask_dots=_need_int(row, "MASK"),
        delay_dots=_need_int(row, "DELAY"),
    )


def _build_config(
    head: HeadGeometry,
    firing: FiringTable,
    encoder: ShaftEncoder | None,
    photocell: Photocell,
    mode_row: dict[str, str] | None,
) -> PrinterConfig:
    if mode_row is None:
        return PrinterConfig(head=head, firing=firing, photocell=photocell, encoder=encoder)

    raw_mode = _need(mode_row, "MODE").upper()
    try:
        print_mode = PrintMode(raw_mode)
    except ValueError:
        raise ProtocolError("print mode %r is not one we know" % (raw_mode,)) from None

    raw_bus = mode_row.get("BUS", "NONE").strip().upper() or "NONE"
    try:
        fieldbus = Fieldbus(raw_bus)
    except ValueError:
        raise ProtocolError("fieldbus %r is not one we know" % (raw_bus,)) from None

    speed = _need_int(mode_row, "SPEED")
    return PrinterConfig(
        head=head,
        firing=firing,
        photocell=photocell,
        encoder=encoder,
        speed_source=SpeedSource.ENCODER if encoder is not None else SpeedSource.FIXED,
        autospeed_mm_per_min=speed,
        print_mode=print_mode,
        fieldbus=fieldbus,
        repeat_count=_need_int(mode_row, "REPEAT"),
        repeat_gap_mm=_need_int(mode_row, "GAP"),
        high_fire=_flag(mode_row, "HFIRE"),
    )


def render_config_table(config: PrinterConfig) -> str:
    """Turn a configuration back into the payload CONFIG would have returned.

    Used by the simulator and by the round trip test. Keeping the writer next to
    the reader is the only way the two stay in step.
    """
    rows: list[str] = [
        "SEC=HEAD,HDPI=%d,VDPI=%d,GROUPS=%d"
        % (config.head.horizontal_dpi, config.head.vertical_dpi, len(config.head.groups))
    ]
    for group in config.head.groups:
        pens = _PEN_LIST_SEPARATOR.join(str(pen.index) for pen in group.pens)
        rows.append(
            "SEC=GROUP,IDX=%d,ORIEN=%s,REV=%d,OFF=%d,SPACE=%d,PENS=%s"
            % (
                group.index,
                group.orientation.value,
                int(group.reverse),
                group.offset_dots,
                group.spacing_dots,
                pens,
            )
        )
        for pen in group.pens:
            rows.append(
                "SEC=PEN,IDX=%d,ROWS=%d,VOFF=%d,HOFF=%d"
                % (pen.index, pen.rows, pen.vertical_offset_dots, pen.horizontal_offset_dots)
            )
    for profile in config.firing.profiles:
        rows.append(
            "SEC=FIRING,PEN=%d,MV=%d,PULSE=%d,WARM=%d,ROHM=%d"
            % (
                profile.pen,
                profile.millivolts,
                profile.pulse_ns,
                profile.warm_ns,
                profile.resistance_mohm,
            )
        )
    if config.encoder is not None:
        rows.append(
            "SEC=ENC,PPR=%d,WHEEL=%d,REV=%d"
            % (
                config.encoder.pulses_per_rev,
                config.encoder.wheel_mm,
                int(config.encoder.reverse),
            )
        )
    rows.append(
        "SEC=CELL,CH=%d,MODE=%s,MASK=%d,DELAY=%d"
        % (
            config.photocell.channel,
            config.photocell.mode.value,
            config.photocell.mask_dots,
            config.photocell.delay_dots,
        )
    )
    rows.append(
        "SEC=MODE,MODE=%s,BUS=%s,SPEED=%d,HFIRE=%d,REPEAT=%d,GAP=%d"
        % (
            config.print_mode.value,
            config.fieldbus.value,
            config.autospeed_mm_per_min,
            int(config.high_fire),
            config.repeat_count,
            config.repeat_gap_mm,
        )
    )
    return ";".join(rows)
