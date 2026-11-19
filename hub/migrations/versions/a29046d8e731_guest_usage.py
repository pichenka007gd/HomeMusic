"""Persist anonymous quotas without storing client addresses."""

import sqlalchemy as sa
from alembic import op

revision = "a29046d8e731"
down_revision = "9f6c34e1adb2"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table("guest_usage", sa.Column("profile", sa.String(64), primary_key=True),
                    sa.Column("action", sa.String(16), primary_key=True),
                    sa.Column("minute", sa.Integer(), nullable=False),
                    sa.Column("requests", sa.Integer(), nullable=False))
    op.create_index("ix_guest_usage_minute", "guest_usage", ["minute"])


def downgrade() -> None:
    op.drop_table("guest_usage")
