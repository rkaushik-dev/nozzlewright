"""Who changed what.

Revision ID: 0004_audit_entries
Revises: 0003_cartridge_fittings
Create Date: 2025-04-28
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0004_audit_entries"
down_revision = "0003_cartridge_fittings"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """Add the audit trail.

    No foreign key to the printer table on purpose. A machine that is taken off
    the site must not take the record of what was done to it with it, and the
    cascade would do exactly that.
    """
    op.create_table(
        "audit_entries",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("actor", sa.String(32), nullable=False, server_default=""),
        sa.Column("action", sa.String(32), nullable=False),
        sa.Column("printer_id", sa.String(32), nullable=False, server_default=""),
        sa.Column("subject", sa.String(64), nullable=False, server_default=""),
        sa.Column("before", sa.String(255), nullable=False, server_default=""),
        sa.Column("after", sa.String(255), nullable=False, server_default=""),
        sa.Column("detail", sa.String(255), nullable=False, server_default=""),
    )
    op.create_index("ix_audit_entries_actor", "audit_entries", ["actor"])
    op.create_index("ix_audit_entries_action", "audit_entries", ["action"])
    op.create_index("ix_audit_entries_at", "audit_entries", ["at"])
    op.create_index("ix_audit_entries_at_printer", "audit_entries", ["at", "printer_id"])


def downgrade() -> None:
    """Drop it again."""
    op.drop_table("audit_entries")
