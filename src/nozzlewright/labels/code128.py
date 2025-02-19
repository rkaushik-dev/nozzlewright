"""Code 128.

The only linear symbology this machine prints, and the only one most customers
ask for. Everything here comes from ISO 15417.

A symbol is a start character, the data, a check character and a stop pattern.
Every character is six alternating runs of bars and spaces, one to four modules
each, except the stop pattern which has seven. A module is the narrowest bar,
and on a TIJ head one module is a whole number of dot columns, which is why the
element carries a module width in dots rather than a millimetre X dimension.

Code set choice matters more than it looks. Set C packs two digits into one
character, so a fourteen digit batch code is seven characters instead of
fourteen, which on a 300 dpi head at two dots per module is nineteen millimetres
saved. The switching rule below is the one the standard recommends: use C for a
run of four or more digits, or from the start for a run of two.
"""

from __future__ import annotations

from nozzlewright.errors import LabelError

__all__ = [
    "encode",
    "modules_for",
    "PATTERNS",
    "START_A",
    "START_B",
    "START_C",
    "STOP",
    "QUIET_ZONE_MODULES",
]

#: Quiet zone either side, in modules. Ten is the standard minimum.
QUIET_ZONE_MODULES = 10

START_A = 103
START_B = 104
START_C = 105
STOP = 106

_CODE_A = 101
_CODE_B = 100
_CODE_C = 99

# Run widths for every character, bar first. Index is the character value.
PATTERNS: tuple[str, ...] = (
    "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312",
    "132212", "221213", "221312", "231212", "112232", "122132", "122231", "113222",
    "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
    "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321",
    "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
    "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
    "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321",
    "331121", "312113", "312311", "332111", "314111", "221411", "431111", "111224",
    "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
    "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
    "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112",
    "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
    "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412",
    "211214", "211232", "2331112",
)


def _value_in_b(char: str) -> int:
    """Character value in code set B."""
    point = ord(char)
    if not 32 <= point <= 126:
        raise LabelError("code 128 set B cannot carry %r" % (char,))
    return point - 32


def _digit_run(data: str, start: int) -> int:
    """How many digits run consecutively from ``start``."""
    length = 0
    while start + length < len(data) and data[start + length].isdigit():
        length += 1
    return length


def _should_switch_to_c(data: str, index: int) -> bool:
    """True when set C pays for itself from here.

    Four digits is the break even point in the middle of a symbol, because the
    switch costs one character and saves two. At the very start the start
    character doubles as the switch, so two digits are enough.
    """
    run = _digit_run(data, index)
    if index == 0:
        return run >= 2 and run % 2 == 0 or run >= 4
    return run >= 4


def _encode_values(data: str) -> list[int]:
    """Turn text into character values, switching code sets as it pays."""
    if not data:
        raise LabelError("code 128 needs something to encode")

    values: list[int] = []
    index = 0
    in_c = _should_switch_to_c(data, 0)
    values.append(START_C if in_c else START_B)

    while index < len(data):
        if in_c:
            run = _digit_run(data, index)
            if run >= 2:
                pair = data[index : index + 2]
                values.append(int(pair))
                index += 2
                continue
            values.append(_CODE_B)
            in_c = False
            continue
        if _should_switch_to_c(data, index):
            values.append(_CODE_C)
            in_c = True
            continue
        values.append(_value_in_b(data[index]))
        index += 1

    return values


def _checksum(values: list[int]) -> int:
    """Modulo 103 weighted sum. The start character has weight one."""
    total = values[0]
    for position, value in enumerate(values[1:], start=1):
        total += position * value
    return total % 103


def encode(data: str) -> list[int]:
    """Every character value in the symbol, check and stop included."""
    values = _encode_values(data)
    values.append(_checksum(values))
    values.append(STOP)
    return values


def modules_for(data: str, *, quiet_zone: bool = True) -> list[bool]:
    """The symbol as modules, True for a bar.

    Quiet zones are on by default. A barcode printed hard against a box edge or
    against another field is a barcode that does not scan, and the machine has
    no way to know what is next to it, so the safe thing is to always carry the
    zone and let the caller turn it off deliberately.
    """
    modules: list[bool] = []
    if quiet_zone:
        modules.extend([False] * QUIET_ZONE_MODULES)
    for value in encode(data):
        pattern = PATTERNS[value]
        bar = True
        for width in pattern:
            modules.extend([bar] * int(width))
            bar = not bar
    if quiet_zone:
        modules.extend([False] * QUIET_ZONE_MODULES)
    return modules


def width_in_modules(data: str, *, quiet_zone: bool = True) -> int:
    """How wide the symbol is, without building it."""
    values = encode(data)
    total = sum(sum(int(width) for width in PATTERNS[value]) for value in values)
    if quiet_zone:
        total += QUIET_ZONE_MODULES * 2
    return total
