"""The document the interactive docs page is built from.

FastAPI writes a perfectly good OpenAPI document by itself and it is missing
the two things that make the difference to somebody meeting this API for the
first time.

It does not know the key header exists. The dependency that checks it is a
plain function, so the generated document has no security scheme, the docs page
has no place to put a key, and every request from it comes back four hundred
and one with nothing to say what to do about it. Declaring the scheme here puts
an authorise button on the page.

And the tags come out as bare words. A support engineer opening this at eleven
at night wants to know that history is cheap and reads the database, and that
the nozzle test stops the print for a second. That is one sentence per tag and
it saves a phone call.

The document is built once and cached on the app, which is what FastAPI does
anyway. It is rebuilt from scratch if the routes change, which they do not
after start up.
"""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI
from fastapi.openapi.utils import get_openapi

from nozzlewright import __version__
from nozzlewright.api.deps import API_KEY_HEADER

__all__ = ["SECURITY_SCHEME", "TAGS", "install_openapi"]

#: The name the security scheme is referred to by inside the document.
SECURITY_SCHEME = "siteKey"

#: One sentence per group of endpoints, in the order they should be read.
TAGS: tuple[dict[str, str], ...] = (
    {
        "name": "fleet",
        "description": (
            "The site as a whole. Reads the last poll rather than the machines, "
            "so it is cheap and safe to refresh."
        ),
    },
    {
        "name": "printers",
        "description": (
            "One machine at a time. These talk to the machine, so they are as "
            "slow as the link is and a nozzle test stops the print while it runs."
        ),
    },
    {
        "name": "labels",
        "description": (
            "The site label store. Everything is versioned and nothing is ever "
            "overwritten, so a revision can always be compared or gone back to."
        ),
    },
    {
        "name": "history",
        "description": (
            "What was written down. Reads the database, never a machine, and "
            "every listing is paged."
        ),
    },
    {
        "name": "ops",
        "description": (
            "What a support engineer needs on a phone call. All reads, and the "
            "doctor parses every stored label so it is not a dashboard refresh."
        ),
    },
)


def install_openapi(app: FastAPI) -> None:
    """Give one app a document with the key header and the tag notes in it."""

    def build() -> dict[str, Any]:
        if app.openapi_schema:
            return app.openapi_schema
        document = get_openapi(
            title=app.title,
            version=__version__,
            summary=app.summary,
            description=(
                "Fleet control for Halvex coding printers.\n\n"
                "Every endpoint but the wall board takes an API key in the "
                "`%s` header, on a gateway that has any keys configured. A "
                "gateway with none answers anybody, which is deliberate and is "
                "reported by `/health`." % API_KEY_HEADER
            ),
            routes=app.routes,
            tags=[dict(tag) for tag in TAGS],
        )
        document.setdefault("components", {})["securitySchemes"] = {
            SECURITY_SCHEME: {
                "type": "apiKey",
                "in": "header",
                "name": API_KEY_HEADER,
                "description": "A site key. Ask whoever commissioned the gateway.",
            }
        }
        document["security"] = [{SECURITY_SCHEME: []}]
        app.openapi_schema = document
        return document

    app.openapi = build  # type: ignore[method-assign]
