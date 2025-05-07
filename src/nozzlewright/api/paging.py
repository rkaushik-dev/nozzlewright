"""Handing back long lists.

A month of readings on one machine is a hundred and seventy thousand rows. A
client asking for them all gets a gateway that stops answering anything else
while it builds a list, so every listing endpoint pages, and pages the same way.

Offset paging rather than cursors. The rows a client is paging through are
append only and ordered by time, so the thing that breaks offset paging, rows
being inserted in the middle, cannot happen here. Cursors would be the right
answer for a table people edit, and this is not one.

The window is capped and the cap is not negotiable by the client. A page size
parameter that a client can set to a million is a denial of service with extra
steps.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Generic, TypeVar

from fastapi import Query
from pydantic import BaseModel, Field

__all__ = ["DEFAULT_PAGE", "MAX_PAGE", "Page", "PageParams"]

#: The most rows one request can ask for.
MAX_PAGE = 500

#: What a request gets when it does not say.
DEFAULT_PAGE = 50

T = TypeVar("T")


class PageParams(BaseModel):
    """Where in a listing a request is."""

    limit: int = Field(default=DEFAULT_PAGE, ge=1, le=MAX_PAGE)
    offset: int = Field(default=0, ge=0)

    @property
    def stop(self) -> int:
        """The index one past the last row this page wants."""
        return self.offset + self.limit

    def slice(self, rows: Sequence[T]) -> list[T]:
        """The rows this page covers, out of a list that is already loaded."""
        return list(rows[self.offset : self.stop])


def page_params(
    limit: int = Query(default=DEFAULT_PAGE, ge=1, le=MAX_PAGE, description="Rows per page."),
    offset: int = Query(default=0, ge=0, description="Rows to skip."),
) -> PageParams:
    """The paging dependency every listing route uses."""
    return PageParams(limit=limit, offset=offset)


class Page(BaseModel, Generic[T]):
    """One page of a listing.

    ``total`` is the number of rows behind the listing when that is cheap to
    know and ``None`` when it is not. A count over a table of millions costs
    more than the page it describes, and a client that only wants to know
    whether there is more can look at ``has_more``.
    """

    items: list[T] = Field(default_factory=list)
    limit: int = DEFAULT_PAGE
    offset: int = 0
    total: int | None = None
    has_more: bool = False

    @classmethod
    def of(
        cls,
        items: Sequence[T],
        params: PageParams,
        *,
        total: int | None = None,
        has_more: bool | None = None,
    ) -> Page[T]:
        """Build a page around rows that have already been narrowed."""
        rows = list(items)
        more = has_more
        if more is None:
            more = len(rows) >= params.limit if total is None else params.stop < total
        return cls(
            items=rows,
            limit=params.limit,
            offset=params.offset,
            total=total,
            has_more=more,
        )

    @classmethod
    def of_all(cls, items: Sequence[T], params: PageParams) -> Page[T]:
        """Build a page by slicing a list that is already in memory.

        Only for listings that are small by construction, like the machines on a
        site. Anything that grows with time is narrowed in the query.
        """
        return cls(
            items=params.slice(items),
            limit=params.limit,
            offset=params.offset,
            total=len(items),
            has_more=params.stop < len(items),
        )
