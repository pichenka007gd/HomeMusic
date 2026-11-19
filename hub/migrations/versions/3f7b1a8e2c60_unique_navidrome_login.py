"""Merge duplicate Hub users and match Navidrome's username collation."""

import sqlalchemy as sa
from alembic import op

revision = "3f7b1a8e2c60"
down_revision = "a3c8e5f17b92"
branch_labels = depends_on = None


def _merge_overlapping_rows(conn, ids):
    # Keep the newest search, combine usage, and resume each Navidrome sync at its latest checkpoint.
    for table, columns, keys, update in (
        ("search_history", "query_key, query, searched_at", "user_id, query_key",
         "query = excluded.query, searched_at = excluded.searched_at "
         "WHERE excluded.searched_at > search_history.searched_at"),
        ("assistant_daily_usage", "day, messages", "user_id, day",
         "messages = assistant_daily_usage.messages + excluded.messages"),
        ("navidrome_sync", "last_scrobble_id, synced_at, favorites_synced_at, favorites_bootstrapped_at", "user_id",
         "last_scrobble_id = max(navidrome_sync.last_scrobble_id, excluded.last_scrobble_id), "
         + ", ".join(f"{col} = coalesce(max(navidrome_sync.{col}, excluded.{col}), "
                     f"navidrome_sync.{col}, excluded.{col})"
                     for col in ("synced_at", "favorites_synced_at", "favorites_bootstrapped_at"))),
    ):
        conn.execute(sa.text(f"""
            INSERT INTO {table} (user_id, {columns})
            SELECT :keep, {columns} FROM {table} WHERE user_id = :drop
            ON CONFLICT ({keys}) DO UPDATE SET {update}
        """), ids)
        conn.execute(sa.text(f"DELETE FROM {table} WHERE user_id = :drop"), ids)

    # Event IDs are idempotency keys. Keep one copy of each event, hidden item and mix settings row.
    for table, match in (
        ("play_events", "existing.event_id = duplicate.event_id"),
        ("smart_hidden", "existing.context = duplicate.context AND existing.fingerprint = duplicate.fingerprint"),
        ("mix_settings", "1 = 1"),
    ):
        conn.execute(sa.text(f"""
            DELETE FROM {table} AS duplicate
            WHERE duplicate.user_id = :drop AND EXISTS (
                SELECT 1 FROM {table} AS existing WHERE existing.user_id = :keep AND {match}
            )
        """), ids)


def _merge_user(conn, references, primary, duplicate):
    ids = {"keep": primary["id"], "drop": duplicate["id"]}
    for field in ("email", "google_sub"):
        if primary[field] and duplicate[field] and primary[field] != duplicate[field]:
            raise RuntimeError(
                f"Hub users {ids['keep']} and {ids['drop']} share a Navidrome login "
                f"and have different {field} links. Resolve the linked accounts before merging."
            )

    _merge_overlapping_rows(conn, ids)
    for table, column in references:
        conn.execute(sa.text(f"UPDATE {table} SET {column} = :keep WHERE {column} = :drop"), ids)
    conn.execute(sa.text("""
        UPDATE jobs SET payload_json = json_set(payload_json, '$.user_id', :keep)
        WHERE kind = 'mix_build' AND json_extract(payload_json, '$.user_id') = :drop
    """), ids)

    conn.execute(sa.text("DELETE FROM users WHERE id = :drop"), ids)
    # Keep the older Hub ID and its settings. A subsequent Navidrome login refreshes admin privileges.
    use_google_link = not primary["google_sub"] and duplicate["google_sub"]
    last_seen = max((r["last_seen_at"] for r in (primary, duplicate) if r["last_seen_at"]), default=None)
    conn.execute(sa.text("""
        UPDATE users SET email = :email, google_sub = :google_sub, google_email = :google_email,
            nd_password_enc = :nd_password_enc, last_seen_at = :last_seen_at, is_admin = :is_admin
        WHERE id = :keep
    """), {
        "keep": ids["keep"], "email": primary["email"] or duplicate["email"],
        "google_sub": primary["google_sub"] or duplicate["google_sub"],
        "google_email": (duplicate if use_google_link else primary)["google_email"],
        "nd_password_enc": (duplicate if use_google_link else primary)["nd_password_enc"],
        "last_seen_at": last_seen, "is_admin": bool(primary["is_admin"] and duplicate["is_admin"]),
    })


def upgrade():
    conn = op.get_bind()
    names = conn.execute(sa.text("""
        SELECT nd_username FROM users GROUP BY nd_username COLLATE NOCASE HAVING count(*) > 1
        ORDER BY nd_username COLLATE NOCASE
    """)).scalars().all()
    if names:
        quote = conn.dialect.identifier_preparer.quote
        references = [
            (quote(table), quote(fk["constrained_columns"][0]))
            for (_, table), foreign_keys in sa.inspect(conn).get_multi_foreign_keys().items()
            for fk in foreign_keys if fk["referred_table"] == "users" and fk["referred_columns"] == ["id"]
        ]
        for name in names:
            users = conn.execute(sa.text("""
                SELECT * FROM users WHERE nd_username = :name COLLATE NOCASE ORDER BY id
            """), {"name": name}).mappings().all()
            primary, *duplicates = users
            for duplicate in duplicates:
                _merge_user(conn, references, primary, duplicate)
                primary = conn.execute(sa.text("SELECT * FROM users WHERE id = :id"),
                                       {"id": primary["id"]}).mappings().one()
    op.create_index("uq_users_nd_username_nocase", "users", [sa.text("nd_username COLLATE NOCASE")],
                    unique=True, if_not_exists=True)


def downgrade():
    op.drop_index("uq_users_nd_username_nocase", table_name="users")
