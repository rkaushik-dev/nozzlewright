"""Links to a printer."""

from __future__ import annotations

from nozzlewright.transport.base import Transport, TransportStats
from nozzlewright.transport.loopback import LoopbackTransport, Responder

__all__ = ["Transport", "TransportStats", "LoopbackTransport", "Responder"]
