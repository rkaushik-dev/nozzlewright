"""Handheld fleet controller for Halvex coding printers."""

from __future__ import annotations

__all__ = ["__version__", "PROTOCOL_REVISION", "SUPPORTED_FAMILIES"]

__version__ = "0.1.0"

#: Revision of the Halvex line protocol this package targets. The printers report
#: their own revision in the VERSION response and we refuse anything newer.
PROTOCOL_REVISION = 2

#: Machine families the controller knows how to talk to. Anything else is
#: rejected at session setup rather than half way through a print run.
SUPPORTED_FAMILIES = ("HVX4PEN", "HVX8PEN", "HVXDOD", "HVXLCP")
