"""Print runs.

Revision ID: 0002_job_runs
Revises: 0001_initial
Create Date: 2025-04-07
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0002_job_runs"
down_revision = "0001_initial"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """Add the table that records what was printed and when."""
    op.create_table(
        "job_runs",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "printer_pk",
            sa.Integer(),
            sa.ForeignKey("printers.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("label", sa.String(24), nullable=False),
        sa.Column("label_revision", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("outcome", sa.String(16), nullable=False, server_default=""),
        sa.Column("target", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("printed", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("started_counter", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("elapsed_s", sa.Float(), nullable=False, server_default="0"),
        sa.Column("operator", sa.String(32), nullable=False, server_default=""),
        sa.Column("detail", sa.String(255), nullable=False, server_default=""),
    )
    op.create_index("ix_job_runs_label", "job_runs", ["label"])
    op.create_index("ix_job_runs_printer_started", "job_runs", ["printer_pk", "started_at"])


def downgrade() -> None:
    """Drop it again."""
    op.drop_table("job_runs")
