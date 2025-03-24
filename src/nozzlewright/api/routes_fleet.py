"""Site wide routes.

The wall board and the alarm screen. Everything here reads what the poller and
the recorder already collected, so a page refresh costs a query and not forty
radio round trips.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from nozzlewright import __version__
from nozzlewright.api.deps import Services, get_services, get_session, require_key
from nozzlewright.api.schemas import AlarmOut, HealthOut, SiteOut, StatusOut
from nozzlewright.storage.repo import AlarmRepo, PrinterRepo

router = APIRouter(tags=["fleet"])


@router.get("/health", response_model=HealthOut)
def health(
    services: Services = Depends(get_services), session: Session = Depends(get_session)
) -> HealthOut:
    """Liveness, and enough detail to tell a broken gateway from an idle one."""
    try:
        count = len(PrinterRepo(session).all())
        database = "up"
    except Exception:
        count = 0
        database = "down"
    return HealthOut(
        ok=database == "up",
        version=__version__,
        site_id=services.settings.site_id,
        printers=count,
        database=database,
    )


@router.get("/fleet/status", response_model=list[StatusOut], dependencies=[Depends(require_key)])
def fleet_status(services: Services = Depends(get_services)) -> list[StatusOut]:
    """The last reading of every machine."""
    if services.poller is None:
        return []
    out: list[StatusOut] = []
    for printer_id in sorted(services.poller.snapshot()):
        result = services.poller.snapshot()[printer_id]
        if result.status is None:
            out.append(StatusOut.unreachable(result))
        else:
            out.append(StatusOut.from_domain(printer_id, result.status))
    return out


@router.get("/fleet/summary", response_model=SiteOut, dependencies=[Depends(require_key)])
def fleet_summary(services: Services = Depends(get_services)) -> SiteOut:
    """The rollup a wall board shows."""
    site = services.stats.site()
    worst = "info"
    if services.poller is not None:
        severities = [
            result.severity.value for result in services.poller.snapshot().values()
        ]
        for level in ("stop", "warning"):
            if level in severities:
                worst = level
                break
    return SiteOut(
        site_id=services.settings.site_id,
        machines=site.machines,
        printing=site.printing_now,
        down=site.down,
        printed=site.printed,
        availability=round(site.availability, 4),
        utilisation=round(site.utilisation, 4),
        worst_severity=worst,
    )


@router.get("/fleet/alarms", response_model=list[AlarmOut], dependencies=[Depends(require_key)])
def fleet_alarms(
    printer_id: str | None = None, session: Session = Depends(get_session)
) -> list[AlarmOut]:
    """Every alarm still up, newest first."""
    rows = AlarmRepo(session).active(printer_id)
    repo = PrinterRepo(session)
    by_pk = {row.id: row.printer_id for row in repo.all()}
    return [AlarmOut.from_row(by_pk.get(row.printer_pk, ""), row) for row in rows]


@router.post(
    "/fleet/alarms/{printer_id}/{name}/ack",
    response_model=AlarmOut,
    dependencies=[Depends(require_key)],
)
def acknowledge_alarm(
    printer_id: str, name: str, who: str = "", session: Session = Depends(get_session)
) -> AlarmOut:
    """Record that somebody is dealing with an alarm."""
    repo = PrinterRepo(session)
    printer = repo.get(printer_id)
    if printer is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="no machine called %s" % printer_id
        )
    row = AlarmRepo(session).acknowledge(printer, name, who or "unknown")
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="%s has no open alarm called %s" % (printer_id, name),
        )
    return AlarmOut.from_row(printer_id, row)
