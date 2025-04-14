"""Which cartridge was in which pen.

Revision ID: 0003_cartridge_fittings
Revises: 0002_job_runs
Create Date: 2025-04-14
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0003_cartridge_fittings"
down_revision = "0002_job_runs"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """Add the fitting history."""
    op.create_table(
        "cartridge_fittings",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "printer_pk",
            sa.Integer(),
            sa.ForeignKey("printers.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("pen", sa.Integer(), nullable=False),
        sa.Column("serial", sa.String(32), nullable=False),
        sa.Column("part_number", sa.String(32), nullable=False, server_default=""),
        sa.Column("ink_family", sa.String(16), nullable=False, server_default=""),
        sa.Column("fitted_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("removed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("first_level", sa.Integer(), nullable=False, server_default="100"),
        sa.Column("last_level", sa.Integer(), nullable=False, server_default="100"),
        sa.Column("readings", sa.Integer(), nullable=False, server_default="1"),
    )
    op.create_index("ix_cartridge_fittings_serial", "cartridge_fittings", ["serial"])
    op.create_index(
        "ix_cartridge_fittings_open",
        "cartridge_fittings",
        ["printer_pk", "pen", "removed_at"],
    )


def downgrade() -> None:
    """Drop it again."""
    op.drop_table("cartridge_fittings")
