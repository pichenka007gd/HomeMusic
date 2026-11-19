"""An update folds case duplicates into the original account."""

import sqlite3
from contextlib import closing
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config


@pytest.fixture
def old_db(db_file):
    root = Path(__file__).resolve().parent.parent
    cfg = Config(str(root / "alembic.ini"))
    cfg.set_main_option("script_location", str(root / "migrations"))
    cfg.set_main_option("sqlalchemy.url", f"sqlite:///{db_file.as_posix()}")
    cfg.attributes["configure_logger"] = False
    command.downgrade(cfg, "a3c8e5f17b92")
    with closing(sqlite3.connect(db_file)) as db, db:
        db.execute("PRAGMA foreign_keys = ON")
        db.executescript("""
            INSERT INTO users (id, nd_username, nd_password_enc, is_admin, created_at, last_seen_at)
            VALUES (3, 'CaseTest', 'primary', 0, '2025-04-01', '2025-04-03'),
                   (4, 'casetest', 'duplicate', 0, '2025-04-02', '2025-04-04');
            INSERT INTO sessions (id, user_id, expires_at, created_at)
            VALUES ('primary-session', 3, '2099-01-01', '2025-04-01'),
                   ('duplicate-session', 4, '2099-01-01', '2025-04-02');
        """)
    return cfg


def test_upgrade_preserves_sessions_and_history_and_prevents_new_duplicates(old_db, db_file):
    with closing(sqlite3.connect(db_file)) as db, db:
        db.executescript("""
            UPDATE users SET email = 'case@example.test', google_sub = 'sub',
                             google_email = 'google@example.test' WHERE id = 4;
            INSERT INTO download_batches (id, user_id, origin, created_at)
            VALUES (1, 4, 'library', '2025-04-02');
            INSERT INTO download_items (id, batch_id, user_id, query_json, status, progress,
                                        candidates_json, warnings, force, created_at, updated_at)
            VALUES (1, 1, 4, '{}', 'done', 1, '[]', '[]', 0, '2025-04-02', '2025-04-02');
            INSERT INTO play_events (user_id, event_id, ts, kind, client, received_at)
            VALUES (3, 'first', '2025-04-01', 'finish', 'pwa', '2025-04-01'),
                   (4, 'first', '2025-04-01', 'finish', 'pwa', '2025-04-01'),
                   (4, 'second', '2025-04-02', 'finish', 'pwa', '2025-04-02');
            INSERT INTO search_history VALUES (3, 'kino', 'Kino', '2025-04-01'),
                                              (4, 'kino', 'KINO', '2025-04-02');
            INSERT INTO assistant_daily_usage VALUES (3, '2025-04-02', 1), (4, '2025-04-02', 2);
            INSERT INTO navidrome_sync (user_id, last_scrobble_id, synced_at, favorites_synced_at)
            VALUES (3, 10, '2025-04-01', '2025-04-01'), (4, 12, '2025-04-02', NULL);
        """)

    command.upgrade(old_db, "head")

    with closing(sqlite3.connect(db_file)) as db, db:
        assert db.execute("""
            SELECT id, nd_username, nd_password_enc, email, google_sub, google_email, last_seen_at FROM users
        """).fetchall() == [(3, "CaseTest", "duplicate", "case@example.test", "sub", "google@example.test", "2025-04-04")]
        assert db.execute("SELECT DISTINCT user_id FROM sessions").fetchall() == [(3,)]
        assert db.execute("SELECT count(*) FROM sessions").fetchone() == (2,)
        assert db.execute("SELECT user_id FROM download_batches WHERE id = 1").fetchone() == (3,)
        assert db.execute("SELECT user_id, status FROM download_items WHERE id = 1").fetchone() == (3, "done")
        assert db.execute("SELECT user_id, event_id FROM play_events ORDER BY event_id").fetchall() == [
            (3, "first"), (3, "second"),
        ]
        assert db.execute("SELECT user_id, query FROM search_history").fetchall() == [(3, "KINO")]
        assert db.execute("SELECT user_id, messages FROM assistant_daily_usage").fetchall() == [(3, 3)]
        assert db.execute("SELECT user_id, last_scrobble_id, favorites_synced_at FROM navidrome_sync").fetchall() == [
            (3, 12, "2025-04-01"),
        ]
        assert db.execute("PRAGMA foreign_key_check").fetchall() == []
        with pytest.raises(sqlite3.IntegrityError):
            db.execute("INSERT INTO users (nd_username, nd_password_enc, is_admin, created_at) "
                       "VALUES ('CASETEST', 'unused', 0, '2025-04-05')")


def test_upgrade_stops_without_losing_conflicting_google_links(old_db, db_file):
    with closing(sqlite3.connect(db_file)) as db, db:
        db.executescript("""
            UPDATE users SET google_sub = 'google-' || id;
            INSERT INTO users (id, nd_username, nd_password_enc, is_admin, created_at)
            VALUES (1, 'Amber', 'primary', 0, '2025-04-01'), (2, 'amber', 'duplicate', 0, '2025-04-02');
        """)

    with pytest.raises(RuntimeError, match="users 3 and 4 .*google_sub"):
        command.upgrade(old_db, "head")

    with closing(sqlite3.connect(db_file)) as db:
        assert db.execute("SELECT id, google_sub FROM users ORDER BY id").fetchall() == [
            (1, None), (2, None), (3, "google-3"), (4, "google-4"),
        ]
        assert db.execute("SELECT user_id FROM sessions ORDER BY user_id").fetchall() == [(3,), (4,)]
