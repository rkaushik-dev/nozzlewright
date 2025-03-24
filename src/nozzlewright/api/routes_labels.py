"""Label routes.

Labels are stored as source text and parsed on the way in, so a label that
cannot be parsed never reaches the database and a client finds out at the
moment it uploads rather than at the moment a line tries to print it.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from nozzlewright.api.deps import get_session, require_key
from nozzlewright.api.schemas import LabelIn, LabelOut
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.store import content_hash
from nozzlewright.labels.writer import render_label_source
from nozzlewright.storage.repo import LabelRepo

router = APIRouter(prefix="/labels", tags=["labels"], dependencies=[Depends(require_key)])


@router.get("", response_model=list[LabelOut])
def list_labels(session: Session = Depends(get_session)) -> list[LabelOut]:
    """The newest revision of every label."""
    repo = LabelRepo(session)
    out: list[LabelOut] = []
    for name in repo.names():
        latest = repo.latest(name)
        if latest is not None:
            out.append(LabelOut.from_row(latest))
    return out


@router.get("/{name}", response_model=LabelOut)
def get_label(name: str, session: Session = Depends(get_session)) -> LabelOut:
    """The newest revision of one label, source included."""
    latest = LabelRepo(session).latest(name)
    if latest is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="no label called %s" % name
        )
    return LabelOut.from_row(latest, with_source=True)


@router.get("/{name}/revisions", response_model=list[LabelOut])
def list_revisions(name: str, session: Session = Depends(get_session)) -> list[LabelOut]:
    """Every revision of one label, oldest first."""
    rows = LabelRepo(session).revisions(name)
    if not rows:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="no label called %s" % name
        )
    return [LabelOut.from_row(row) for row in rows]


@router.put("/{name}", response_model=LabelOut, status_code=status.HTTP_201_CREATED)
def put_label(
    name: str, body: LabelIn, session: Session = Depends(get_session)
) -> LabelOut:
    """Store a label. Parsing it is the validation."""
    template = parse_label(body.source)
    if template.name != name:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="the label calls itself %s and the path says %s" % (template.name, name),
        )
    # Normalise before hashing, so an upload that only differs in spacing does
    # not make a revision nobody asked for.
    source = render_label_source(template)
    row = LabelRepo(session).save(
        name=template.name,
        source=source,
        digest=content_hash(source),
        height_dots=template.height_dots,
        interactive=bool(template.interactive_fields()),
    )
    return LabelOut.from_row(row, with_source=True)
