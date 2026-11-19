"""Remember Yandex import usage even when users delete their imports."""

import sqlalchemy as sa
from alembic import op

revision = "9f6c34e1adb2"
down_revision = "3f7b1a8e2c60"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("users", sa.Column("yandex_import_at", sa.DateTime(), nullable=True))
    op.execute("""
        UPDATE users SET yandex_import_at = (
            SELECT max(created_at) FROM imports WHERE user_id = users.id AND source = 'yandex'
        )
    """)


def downgrade() -> None:
    op.drop_column("users", "yandex_import_at")
