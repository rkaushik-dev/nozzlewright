"""The first schema.

Revision ID: 0001_initial
Revises:
Create Date: 2025-03-20
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    """Create every table the first release needs."""
    op.create_table(
        "printers",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("printer_id", sa.String(32), nullable=False),
        sa.Column("endpoint", sa.String(128), nullable=False),
        sa.Column("kind", sa.String(16), nullable=False, server_default="tcp"),
        sa.Column("line", sa.String(32), nullable=False, server_default=""),
        sa.Column("description", sa.String(255), nullable=False, server_default=""),
        sa.Column("family", sa.String(16), nullable=False, server_default=""),
        sa.Column("firmware", sa.String(32), nullable=False, server_default=""),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("seen_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_printers_printer_id", "printers", ["printer_id"], unique=True)

    op.create_table(
        "poll_samples",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "printer_pk",
            sa.Integer(),
            sa.ForeignKey("printers.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("reachable", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("state", sa.String(16), nullable=False, server_default=""),
        sa.Column("alarms", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("printed", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("head_temperature_c", sa.Float(), nullable=True),
        sa.Column("line_speed_mm_per_min", sa.Float(), nullable=True),
        sa.Column("duration_s", sa.Float(), nullable=False, server_default="0"),
        sa.Column("error", sa.String(255), nullable=False, server_default=""),
    )
    op.create_index("ix_poll_samples_at", "poll_samples", ["at"])
    op.create_index("ix_poll_samples_printer_at", "poll_samples", ["printer_pk", "at"])

    op.create_table(
        "alarm_records",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "printer_pk",
            sa.Integer(),
            sa.ForeignKey("printers.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(32), nullable=False),
        sa.Column("severity", sa.String(16), nullable=False, server_default="warning"),
        sa.Column("raised_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("cleared_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("seen", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("acknowledged_by", sa.String(32), nullable=False, server_default=""),
    )
    op.create_index("ix_alarm_records_name", "alarm_records", ["name"])
    op.create_index("ix_alarm_records_open", "alarm_records", ["printer_pk", "cleared_at"])

    op.create_table(
        "label_revisions",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(24), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("digest", sa.String(32), nullable=False),
        sa.Column("source", sa.String(8192), nullable=False),
        sa.Column("height_dots", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("interactive", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_by", sa.String(32), nullable=False, server_default=""),
        sa.UniqueConstraint("name", "revision", name="uq_label_revision"),
    )
    op.create_index("ix_label_revisions_name", "label_revisions", ["name"])

    op.create_table(
        "config_snapshots",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "printer_pk",
            sa.Integer(),
            sa.ForeignKey("printers.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("taken_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("digest", sa.String(32), nullable=False),
        sa.Column("payload", sa.String(8192), nullable=False),
        sa.Column("reason", sa.String(64), nullable=False, server_default=""),
        sa.Column("taken_by", sa.String(32), nullable=False, server_default=""),
    )
    op.create_index("ix_config_snapshots_digest", "config_snapshots", ["digest"])
    op.create_index(
        "ix_config_snapshots_printer_at", "config_snapshots", ["printer_pk", "taken_at"]
    )


def downgrade() -> None:
    """Drop everything, in the order the foreign keys allow."""
    op.drop_table("config_snapshots")
    op.drop_table("label_revisions")
    op.drop_table("alarm_records")
    op.drop_table("poll_samples")
    op.drop_table("printers")
