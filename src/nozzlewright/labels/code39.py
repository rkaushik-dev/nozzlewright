"""Code 39, because half the plants still ask for it.

Code 128 is denser, carries the full character set and has a mandatory check
digit, and it is what anybody specifying a new label picks. Code 39 is what is
already printed on the racking, in the work instructions, and in the twenty
year old warehouse system that will be replaced next year for the ninth year
running. So it has to be here.

The encoding is simple in a way Code 128 is not. Every character is nine
elements, five bars and four spaces, three of the nine wide and six narrow,
which is where the old name three of nine comes from. There are no shift
characters, no mode switching and no state: one character in, one pattern out.

Two things do need deciding. The check character is optional in the standard,
which in practice means it is optional until the scanner at the far end says
otherwise, so it is off by default and switched on per element. And the ratio
of a wide element to a narrow one is settable between two and three, where wide
bars scan better and narrow ones fit. Two and a half is common on printed
labels and impossible on a dot matrix head, so the ratio here is a whole number
of dots and nothing rounds.

Every symbol carries the start and stop asterisk. A reader that gets a symbol
without them reads nothing at all, which is at least an honest failure.
"""

from __future__ import annotations

from nozzlewright.errors import LabelError

__all__ = [
    "ALPHABET",
    "MAX_RATIO",
    "MIN_RATIO",
    "QUIET_ZONE_MODULES",
    "check_character",
    "encode",
    "modules_for",
    "normalise",
    "width_in_modules",
]

#: Characters Code 39 can carry, in their check character order.
ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%"

#: Narrow modules of clear space either side of the symbol.
QUIET_ZONE_MODULES = 10

#: The narrowest and widest a wide element may be, in narrow modules.
MIN_RATIO = 2
MAX_RATIO = 3

#: Which of the nine elements of each character are wide. One bit per element,
#: most significant first, bars and spaces alternating and starting on a bar.
_PATTERNS: dict[str, int] = {
    "0": 0b000110100,
    "1": 0b100100001,
    "2": 0b001100001,
    "3": 0b101100000,
    "4": 0b000110001,
    "5": 0b100110000,
    "6": 0b001110000,
    "7": 0b000100101,
    "8": 0b100100100,
    "9": 0b001100100,
    "A": 0b100001001,
    "B": 0b001001001,
    "C": 0b101001000,
    "D": 0b000011001,
    "E": 0b100011000,
    "F": 0b001011000,
    "G": 0b000001101,
    "H": 0b100001100,
    "I": 0b001001100,
    "J": 0b000011100,
    "K": 0b100000011,
    "L": 0b001000011,
    "M": 0b101000010,
    "N": 0b000010011,
    "O": 0b100010010,
    "P": 0b001010010,
    "Q": 0b000000111,
    "R": 0b100000110,
    "S": 0b001000110,
    "T": 0b000010110,
    "U": 0b110000001,
    "V": 0b011000001,
    "W": 0b111000000,
    "X": 0b010010001,
    "Y": 0b110010000,
    "Z": 0b011010000,
    "-": 0b010000101,
    ".": 0b110000100,
    " ": 0b011000100,
    "$": 0b010101000,
    "/": 0b010100010,
    "+": 0b010001010,
    "%": 0b000101010,
    "*": 0b010010100,
}

#: The character that opens and closes every symbol.
DELIMITER = "*"


def normalise(data: str) -> str:
    """Fold data into what Code 39 can carry, or say why it cannot.

    Lower case is folded up rather than refused. Code 39 has no lower case at
    all, a label that asks for one is a label somebody typed in a hurry, and
    folding it is what every printer on the market does.
    """
    folded = data.upper()
    if DELIMITER in folded:
        raise LabelError("code 39 data cannot hold an asterisk, it delimits the symbol")
    bad = sorted({char for char in folded if char not in _PATTERNS})
    if bad:
        raise LabelError("code 39 cannot carry %s" % ", ".join(repr(char) for char in bad))
    return folded


def check_character(data: str) -> str:
    """The modulo forty three check character for some data.

    Values are the position in the alphabet, summed and taken modulo forty
    three. The delimiter has no value and is never counted, which is why this
    takes the data rather than the finished symbol.
    """
    folded = normalise(data)
    total = sum(ALPHABET.index(char) for char in folded)
    return ALPHABET[total % len(ALPHABET)]


def encode(data: str, *, check: bool = False) -> str:
    """The characters of the finished symbol, delimiters included."""
    folded = normalise(data)
    if not folded:
        raise LabelError("code 39 has nothing to encode")
    body = folded + (check_character(folded) if check else "")
    return DELIMITER + body + DELIMITER


def modules_for(
    data: str, *, check: bool = False, ratio: int = MIN_RATIO, quiet_zone: bool = True
) -> list[bool]:
    """The symbol as modules, True for a bar.

    One narrow module of space sits between characters. It is part of the
    symbol and not decoration: leaving it out runs the last bar of one
    character into the first bar of the next and the reader sees one wide bar
    where there were two narrow ones.
    """
    if not MIN_RATIO <= ratio <= MAX_RATIO:
        raise LabelError(
            "a code 39 wide to narrow ratio of %d is outside %d to %d"
            % (ratio, MIN_RATIO, MAX_RATIO)
        )
    modules: list[bool] = []
    if quiet_zone:
        modules.extend([False] * QUIET_ZONE_MODULES)
    symbol = encode(data, check=check)
    for index, char in enumerate(symbol):
        if index:
            modules.append(False)
        modules.extend(_character_modules(char, ratio))
    if quiet_zone:
        modules.extend([False] * QUIET_ZONE_MODULES)
    return modules


def width_in_modules(
    data: str, *, check: bool = False, ratio: int = MIN_RATIO, quiet_zone: bool = True
) -> int:
    """How wide the symbol is, without building it.

    Every character is six narrow and three wide elements, so its width is a
    fixed six plus three times the ratio, and the gaps between characters are
    one each. Worth having as arithmetic rather than a length, because the lint
    pass asks this question about labels it is not going to render.
    """
    characters = len(normalise(data)) + (1 if check else 0) + 2
    per_character = 6 + 3 * ratio
    total = characters * per_character + (characters - 1)
    if quiet_zone:
        total += QUIET_ZONE_MODULES * 2
    return total


def _character_modules(char: str, ratio: int) -> list[bool]:
    """The nine elements of one character, widest bit first."""
    pattern = _PATTERNS[char]
    modules: list[bool] = []
    bar = True
    for position in range(8, -1, -1):
        wide = bool(pattern >> position & 1)
        modules.extend([bar] * (ratio if wide else 1))
        bar = not bar
    return modules
