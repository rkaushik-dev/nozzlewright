"""The gateway HTTP API.

Small on purpose. The handheld does most of its work over the radio, straight to
the machine, and only comes here for the things that need the whole site: what
is running, what is in alarm, and what a label looked like last Tuesday.

Every error the domain raises has a status code here and nowhere else. A handler
that catches a printer error to turn it into a response is a handler that will
get it slightly different from the next one, so the mapping lives in one place
and the handlers let things through.
"""

from __future__ import annotations

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse

from nozzlewright import __version__
from nozzlewright.api.deps import Services
from nozzlewright.errors import (
    CommandRejected,
    ConfigError,
    LabelError,
    LinkClosed,
    NotAuthenticated,
    PermissionDenied,
    PrinterBusy,
    ProtocolError,
    TransportError,
    NozzlewrightError,
)
from nozzlewright.telemetry.logging import get_logger

__all__ = ["STATUS_FOR", "build_app"]

_log = get_logger("nozzlewright.api")

#: How a domain error becomes an HTTP status. Order matters, the first match
#: wins, so the specific types come before their bases.
STATUS_FOR: tuple[tuple[type[Exception], int], ...] = (
    (PermissionDenied, status.HTTP_403_FORBIDDEN),
    (NotAuthenticated, status.HTTP_401_UNAUTHORIZED),
    (PrinterBusy, status.HTTP_409_CONFLICT),
    (CommandRejected, status.HTTP_502_BAD_GATEWAY),
    (LinkClosed, status.HTTP_504_GATEWAY_TIMEOUT),
    (TransportError, status.HTTP_502_BAD_GATEWAY),
    (ProtocolError, status.HTTP_502_BAD_GATEWAY),
    (ConfigError, status.HTTP_400_BAD_REQUEST),
    (LabelError, status.HTTP_400_BAD_REQUEST),
    (NozzlewrightError, status.HTTP_500_INTERNAL_SERVER_ERROR),
)


def status_for(error: Exception) -> int:
    """The HTTP status one domain error maps to."""
    for kind, code in STATUS_FOR:
        if isinstance(error, kind):
            return code
    return status.HTTP_500_INTERNAL_SERVER_ERROR


def build_app(services: Services) -> FastAPI:
    """Build the application around one services object."""
    app = FastAPI(
        title="Halvex Mobile gateway",
        version=__version__,
        summary="Fleet control for Halvex coding printers",
    )
    app.state.services = services

    @app.exception_handler(NozzlewrightError)
    async def _domain_error(request: Request, error: Exception) -> JSONResponse:
        code = status_for(error)
        printer_id = str(request.path_params.get("printer_id", ""))
        if code >= 500:
            _log.warning(
                "request failed",
                path=request.url.path,
                printer=printer_id,
                error=str(error),
            )
        return JSONResponse(
            status_code=code,
            content={
                "error": type(error).__name__,
                "detail": str(error),
                "printer_id": printer_id,
            },
        )

    from nozzlewright.api.routes_fleet import router as fleet_router
    from nozzlewright.api.routes_history import router as history_router
    from nozzlewright.api.routes_labels import router as labels_router
    from nozzlewright.api.routes_printers import router as printers_router

    app.include_router(printers_router)
    app.include_router(labels_router)
    app.include_router(fleet_router)
    app.include_router(history_router)
    return app
