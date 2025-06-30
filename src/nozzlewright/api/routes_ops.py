"""What somebody needs when a site phones up.

Everything under ``/ops``. It is the same information the console gives, over
HTTP, because half the time the person who can help is not the person who can
get a shell on the box. A support engineer with the API key and a browser can
answer most of a support call without anybody driving anywhere.

Nothing here changes anything. These are all reads, they are all behind the API
key, and the two that could be expensive say what they cost in their own
description rather than being quietly rate limited into uselessness.
"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field

from nozzlewright.api.deps import Services, get_services, require_key
from nozzlewright.doctor import Report, run_checks
from nozzlewright.protocol.coverage import capability_matrix, differing_verbs, upgrade_gains
from nozzlewright.protocol.dialects import HIGHEST_KNOWN_REVISION, KNOWN_FAMILIES
from nozzlewright.storage.janitor import Janitor

__all__ = ["router"]

router = APIRouter(prefix="/ops", tags=["ops"], dependencies=[Depends(require_key)])


class CheckOut(BaseModel):
    """One thing the doctor looked at."""

    name: str
    grade: str
    detail: str = ""
    ok: bool = True


class DoctorOut(BaseModel):
    """Whether this box is fit to run a site."""

    grade: str
    ok: bool
    healthy: bool
    state_dir: str
    checks: list[CheckOut] = Field(default_factory=list)

    @classmethod
    def from_report(cls, report: Report) -> DoctorOut:
        """Build one from a doctor report."""
        return cls(
            grade=report.grade.value,
            ok=report.ok,
            healthy=report.healthy,
            state_dir=str(report.state_dir),
            checks=[CheckOut(**check.as_dict()) for check in report.checks],  # type: ignore[arg-type]
        )


class CapabilityOut(BaseModel):
    """What the machine families this build knows can be asked to do."""

    revision: int
    families: list[str] = Field(default_factory=list)
    #: Only the verbs that are not the same everywhere. The rest is noise.
    differing: dict[str, dict[str, bool]] = Field(default_factory=dict)


class UpgradeOut(BaseModel):
    """What a firmware upgrade would add on one family."""

    family: str
    current: int
    target: int
    gains: list[str] = Field(default_factory=list)


class StorageOut(BaseModel):
    """How big the database is and how much of it is free space."""

    bytes: int = 0
    pages: int = 0
    free_pages: int = 0
    #: True when a vacuum would give a useful amount back.
    worth_vacuuming: bool = False


#: Under this fraction of the file being free pages, a vacuum is not worth the
#: minute it takes on a slow card.
VACUUM_THRESHOLD = 0.25


@router.get("/doctor", response_model=DoctorOut)
def doctor(services: Services = Depends(get_services)) -> DoctorOut:
    """Run the same checks the console does.

    Reads the disk and parses every stored label, so it takes a moment on a
    site with a lot of them. Not something to put on a dashboard refresh.
    """
    state_dir = Path(services.settings.state_dir)
    url = "sqlite:///%s" % (state_dir / "site.sqlite3").as_posix()
    return DoctorOut.from_report(run_checks(state_dir, url))


@router.get("/capabilities", response_model=CapabilityOut)
def capabilities(
    revision: int = Query(
        default=HIGHEST_KNOWN_REVISION, ge=1, le=HIGHEST_KNOWN_REVISION, description="Firmware."
    ),
) -> CapabilityOut:
    """Which verbs each machine family carries, at one firmware revision."""
    matrix = capability_matrix(KNOWN_FAMILIES, revision)
    differing = differing_verbs(KNOWN_FAMILIES, revision)
    return CapabilityOut(
        revision=revision,
        families=list(KNOWN_FAMILIES),
        differing={verb: matrix[verb] for verb in differing},
    )


@router.get("/upgrade/{family}", response_model=UpgradeOut)
def upgrade(
    family: str,
    current: int = Query(default=1, ge=1, description="What the machine is running now."),
) -> UpgradeOut:
    """What a firmware upgrade would buy on one family."""
    wanted = family.upper()
    if wanted not in KNOWN_FAMILIES:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="no machine family called %s" % family,
        )
    return UpgradeOut(
        family=wanted,
        current=current,
        target=HIGHEST_KNOWN_REVISION,
        gains=list(upgrade_gains(wanted, current)),
    )


@router.get("/storage", response_model=StorageOut)
def storage(services: Services = Depends(get_services)) -> StorageOut:
    """How much of the card the history is using.

    The free page count is the interesting number. A file that is mostly free
    pages has had a big sweep and has not been vacuumed since, which is the one
    case where rewriting it is worth the minute it takes.
    """
    janitor = Janitor(factory=services.factory, engine=services.factory.kw["bind"])
    stats = janitor.page_stats()
    if not stats:
        return StorageOut()
    pages = stats.get("pages", 0)
    free = stats.get("free_pages", 0)
    return StorageOut(
        bytes=stats.get("bytes", 0),
        pages=pages,
        free_pages=free,
        worth_vacuuming=bool(pages) and free / pages >= VACUUM_THRESHOLD,
    )
