"""Machine routes.

Everything under ``/printers``. Reads go to the live machine where the answer
has to be current and to the database where it does not, which is the split a
handheld on a slow radio actually needs: a wall board refresh should not put
forty commands on the wire.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy.orm import Session

from nozzlewright.api.deps import (
    Services,
    get_printer_session,
    get_services,
    get_session,
    require_key,
)
from nozzlewright.api.schemas import (
    ApplyIn,
    ApplyOut,
    ConfigOut,
    InkOut,
    NozzleOut,
    PrinterIn,
    PrinterOut,
    RecoveryOut,
    StatusOut,
)
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.printer.operations import (
    apply_config,
    read_config,
    read_ink,
    read_nozzles,
    read_status,
    recover_head,
)
from nozzlewright.printer.snapshot import parse_config_table, render_config_table
from nozzlewright.protocol.codec import parse_table
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.storage.repo import PrinterRepo, SnapshotRepo

router = APIRouter(prefix="/printers", tags=["printers"], dependencies=[Depends(require_key)])


@router.get("", response_model=list[PrinterOut])
def list_printers(
    line: str | None = None,
    enabled: bool | None = None,
    services: Services = Depends(get_services),
    session: Session = Depends(get_session),
) -> list[PrinterOut]:
    """Every machine on the site, optionally filtered."""
    rows = {row.printer_id: row for row in PrinterRepo(session).all()}
    records = services.registry.all()
    if line is not None:
        records = services.registry.on_line(line)
    if enabled is not None:
        records = tuple(item for item in records if item.enabled is enabled)

    out: list[PrinterOut] = []
    for record in records:
        row = rows.get(record.printer_id)
        out.append(
            PrinterOut.from_domain(
                record,
                family="" if row is None else row.family,
                firmware="" if row is None else row.firmware,
                seen_at=None if row is None else row.seen_at,
            )
        )
    return out


@router.post("", response_model=PrinterOut, status_code=status.HTTP_201_CREATED)
def add_printer(
    body: PrinterIn,
    services: Services = Depends(get_services),
    session: Session = Depends(get_session),
) -> PrinterOut:
    """Register a machine, or update one that is already registered."""
    record: PrinterRecord = body.to_domain()
    stored = services.registry.add(record, replace_existing=True)
    PrinterRepo(session).upsert(stored)
    return PrinterOut.from_domain(stored)


@router.get("/{printer_id}", response_model=PrinterOut)
def get_printer(
    printer_id: str,
    services: Services = Depends(get_services),
    session: Session = Depends(get_session),
) -> PrinterOut:
    """One machine."""
    record = services.registry.get(printer_id)
    row = PrinterRepo(session).get(printer_id)
    return PrinterOut.from_domain(
        record,
        family="" if row is None else row.family,
        firmware="" if row is None else row.firmware,
        seen_at=None if row is None else row.seen_at,
    )


@router.delete("/{printer_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_printer(
    printer_id: str,
    services: Services = Depends(get_services),
    session: Session = Depends(get_session),
) -> Response:
    """Take a machine off the site."""
    services.registry.remove(printer_id)
    repo = PrinterRepo(session)
    if repo.get(printer_id) is not None:
        repo.remove(printer_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/{printer_id}/status", response_model=StatusOut)
async def printer_status(
    printer_id: str,
    live: bool = False,
    services: Services = Depends(get_services),
    session_for: PrinterSession = Depends(get_printer_session),
) -> StatusOut:
    """The machine status, from the last poll or straight off the wire."""
    if not live and services.poller is not None:
        cached = services.poller.latest(printer_id)
        if cached is not None:
            if cached.status is None:
                return StatusOut.unreachable(cached)
            return StatusOut.from_domain(printer_id, cached.status)
    return StatusOut.from_domain(printer_id, await read_status(session_for))


@router.get("/{printer_id}/ink", response_model=list[InkOut])
async def printer_ink(
    printer_id: str, session_for: PrinterSession = Depends(get_printer_session)
) -> list[InkOut]:
    """Ink levels, always read live because they move."""
    return [InkOut.from_domain(level) for level in await read_ink(session_for)]


@router.get("/{printer_id}/nozzles", response_model=NozzleOut)
async def printer_nozzles(
    printer_id: str,
    pen: int = Query(default=0, ge=0, le=8, description="One cartridge, or nought for all."),
    session_for: PrinterSession = Depends(get_printer_session),
) -> NozzleOut:
    """Run a nozzle test on the machine and decode it.

    A test stops the print for as long as it takes to fire every nozzle once,
    which is why this is a GET that a wall board must not poll. Nothing caches
    it either, because a cached nozzle test is a lie about a head that has been
    purged since.
    """
    return NozzleOut.from_domain(printer_id, await read_nozzles(session_for, pen))


@router.post("/{printer_id}/recover", response_model=RecoveryOut)
async def printer_recover(
    printer_id: str,
    pen: int = Query(default=0, ge=0, le=8, description="One cartridge, or nought for the worst."),
    drops: int = Query(default=200, ge=1, le=2000, description="Drops to spit."),
    session_for: PrinterSession = Depends(get_printer_session),
) -> RecoveryOut:
    """Purge and wipe whichever cartridge the nozzle test says needs it."""
    before, after = await recover_head(session_for, pen=pen, drops=drops)
    if after is None:
        return RecoveryOut(
            printer_id=printer_id,
            before=before.dead,
            after=before.dead,
            recovered=0,
            advice=before.advice.value,
            retested=False,
        )
    return RecoveryOut(
        printer_id=printer_id,
        before=before.dead,
        after=after.dead,
        recovered=before.dead - after.dead,
        advice=after.advice.value,
        retested=True,
    )


@router.get("/{printer_id}/config", response_model=ConfigOut)
async def printer_config(
    printer_id: str,
    session_for: PrinterSession = Depends(get_printer_session),
    session: Session = Depends(get_session),
) -> ConfigOut:
    """Read the whole configuration and keep a snapshot of it."""
    config = await read_config(session_for)
    payload = render_config_table(config)
    repo = PrinterRepo(session)
    row = repo.get(printer_id)
    if row is not None:
        SnapshotRepo(session).take(row, payload, _digest(payload), reason="api read")
    return ConfigOut.from_domain(printer_id, config, payload)


@router.post("/{printer_id}/config", response_model=ApplyOut)
async def apply_printer_config(
    printer_id: str,
    body: ApplyIn,
    session_for: PrinterSession = Depends(get_printer_session),
) -> ApplyOut:
    """Push a configuration onto a machine."""
    dialect = session_for.dialect
    if dialect is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="the machine has not identified itself yet",
        )
    target = parse_config_table(parse_table(body.payload), family=dialect.family)
    report = await apply_config(
        session_for, target, dry_run=body.dry_run, stop_first=body.stop_first
    )
    return ApplyOut.from_domain(printer_id, report)


@router.post("/{printer_id}/print/start", status_code=status.HTTP_202_ACCEPTED)
async def start_print(
    session_for: PrinterSession = Depends(get_printer_session),
) -> dict[str, str]:
    """Start the machine."""
    await session_for.command("STARTPRINT")
    return {"state": "starting"}


@router.post("/{printer_id}/print/stop", status_code=status.HTTP_202_ACCEPTED)
async def stop_print(
    session_for: PrinterSession = Depends(get_printer_session),
) -> dict[str, str]:
    """Stop the machine."""
    await session_for.command("STOPPRINT")
    return {"state": "stopping"}


@router.post("/{printer_id}/label/{name}", status_code=status.HTTP_202_ACCEPTED)
async def select_label(
    name: str, session_for: PrinterSession = Depends(get_printer_session)
) -> dict[str, str]:
    """Make a stored label the active one."""
    await session_for.command("LABEL", name)
    return {"label": name}


def _digest(payload: str) -> str:
    """The same short hash the label store uses."""
    from nozzlewright.labels.store import content_hash

    return content_hash(payload)
