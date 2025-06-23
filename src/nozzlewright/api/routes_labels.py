"""Label routes.

Labels are stored as source text and parsed on the way in, so a label that
cannot be parsed never reaches the database and a client finds out at the
moment it uploads rather than at the moment a line tries to print it.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from nozzlewright.api.deps import get_session, require_key
from nozzlewright.api.paging import Page, PageParams, page_params
from nozzlewright.api.schemas import DiffOut, LabelIn, LabelOut
from nozzlewright.labels.diff import diff_labels
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


class RevisionPage(Page[LabelOut]):
    """A page of label revisions."""


@router.get("/{name}/revisions", response_model=RevisionPage)
def list_revisions(
    name: str,
    paging: PageParams = Depends(page_params),
    session: Session = Depends(get_session),
) -> RevisionPage:
    """Every revision of one label, oldest first.

    Paged because this one grows for ever. A label somebody edits every week
    for three years is a hundred and fifty revisions, all of them carrying
    their whole source.
    """
    rows = LabelRepo(session).revisions(name)
    if not rows:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="no label called %s" % name
        )
    page = Page.of_all([LabelOut.from_row(row) for row in rows], paging)
    return RevisionPage(**page.model_dump())


@router.get("/{name}/diff", response_model=DiffOut)
def diff_revisions(
    name: str,
    left: int | None = Query(default=None, ge=1, description="Older revision, default previous."),
    right: int | None = Query(default=None, ge=1, description="Newer revision, default latest."),
    session: Session = Depends(get_session),
) -> DiffOut:
    """Compare two stored revisions of one label.

    With no revisions named it compares the last two, which is the question
    somebody asks when a line started printing something different this
    morning and nobody will say what they changed.
    """
    rows = {row.revision: row for row in LabelRepo(session).revisions(name)}
    if not rows:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="no label called %s" % name
        )
    numbers = sorted(rows)
    if len(numbers) < 2 and (left is None or right is None):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="%s has only one revision, there is nothing to compare" % name,
        )
    older = left if left is not None else numbers[-2]
    newer = right if right is not None else numbers[-1]
    for wanted in (older, newer):
        if wanted not in rows:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="%s has no revision %d" % (name, wanted),
            )
    result = diff_labels(parse_label(rows[older].source), parse_label(rows[newer].source))
    return DiffOut.from_domain(result, left=older, right=newer)


@router.put("/{name}", response_model=LabelOut, status_code=status.HTTP_201_CREATED)
def put_label(name: str, body: LabelIn, session: Session = Depends(get_session)) -> LabelOut:
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
