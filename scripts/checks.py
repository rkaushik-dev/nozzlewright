"""Run the checks, the same way everywhere.

There is one list of checks and one place that knows the arguments. Before this
existed the workflow file and the contributing notes had drifted apart, and a
branch could be green in CI and fail on a laptop for a reason nobody could
reproduce.

Usage:

    python scripts/checks.py            run everything
    python scripts/checks.py lint       run one
    python scripts/checks.py --list     say what there is
    python scripts/checks.py --fix      let the formatter and linter write

Exit code is the number of checks that failed, so a shell can test it and CI
gets a non zero on any failure.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Check:
    """One thing worth running before a change goes anywhere."""

    name: str
    argv: tuple[str, ...]
    fix_argv: tuple[str, ...] = ()
    why: str = ""

    def command(self, fix: bool) -> tuple[str, ...]:
        """The arguments to run, with or without letting it write."""
        if fix and self.fix_argv:
            return self.fix_argv
        return self.argv


CHECKS: tuple[Check, ...] = (
    Check(
        name="format",
        argv=("ruff", "format", "--check", "src", "tests", "scripts"),
        fix_argv=("ruff", "format", "src", "tests", "scripts"),
        why="one layout, so a diff shows what changed and not who wrote it",
    ),
    Check(
        name="lint",
        argv=("ruff", "check", "src", "tests", "scripts"),
        fix_argv=("ruff", "check", "--fix", "src", "tests", "scripts"),
        why="the rules in pyproject, which are not negotiable per file",
    ),
    Check(
        name="types",
        argv=("mypy",),
        why="the settings in pyproject, run over src only",
    ),
    Check(
        name="tests",
        argv=("pytest", "-q"),
        why="the whole suite, no markers skipped",
    ),
)


def run(check: Check, *, fix: bool) -> tuple[bool, float]:
    """Run one check and say whether it passed and how long it took."""
    argv = (sys.executable, "-m", *check.command(fix))
    started = time.monotonic()
    completed = subprocess.run(argv, cwd=ROOT, check=False)
    return completed.returncode == 0, time.monotonic() - started


def main(argv: Sequence[str] | None = None) -> int:
    """Run the checks named on the command line, or all of them."""
    parser = argparse.ArgumentParser(description="Run the project checks.")
    parser.add_argument("names", nargs="*", help="Checks to run. Default is all of them.")
    parser.add_argument("--list", action="store_true", help="Say what checks there are.")
    parser.add_argument("--fix", action="store_true", help="Let the tools write.")
    args = parser.parse_args(argv)

    if args.list:
        for check in CHECKS:
            print("%-8s %s" % (check.name, check.why))
        return 0

    wanted = list(CHECKS)
    if args.names:
        known = {check.name: check for check in CHECKS}
        unknown = [name for name in args.names if name not in known]
        if unknown:
            print("no such check: %s" % ", ".join(unknown), file=sys.stderr)
            return 1
        wanted = [known[name] for name in args.names]

    failed = 0
    for check in wanted:
        print("== %s" % check.name, flush=True)
        ok, seconds = run(check, fix=args.fix)
        print("== %s %s in %.1fs" % (check.name, "ok" if ok else "FAILED", seconds), flush=True)
        if not ok:
            failed += 1
    if failed:
        print("%d of %d checks failed" % (failed, len(wanted)), file=sys.stderr)
    return failed


if __name__ == "__main__":
    raise SystemExit(main())
