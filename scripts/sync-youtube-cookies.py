import argparse
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile


def export(profile: Path, output: Path) -> bool:
    source = profile / "cookies.sqlite"
    if not source.is_file():
        raise RuntimeError("Firefox cookie database not found")
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with tempfile.TemporaryDirectory(prefix="snapshot-", dir=output.parent) as folder:
        database = Path(folder) / "cookies.sqlite"
        for suffix in ("", "-wal"):
            path = Path(str(source) + suffix)
            if path.is_file():
                shutil.copyfile(path, Path(str(database) + suffix))
        with sqlite3.connect(database) as connection:
            rows = connection.execute(
                "SELECT host, path, isSecure, expiry, name, value FROM moz_cookies "
                "WHERE (host='youtube.com' OR host LIKE '%.youtube.com') "
                "AND originAttributes='' ORDER BY host, path, name, lastAccessed"
            ).fetchall()
    cookies = {(row[0], row[1], row[4]): row for row in rows}
    if not {"SID", "SAPISID", "LOGIN_INFO"} <= {key[2] for key in cookies}:
        raise RuntimeError("No signed-in YouTube session; previous export preserved")
    lines = ["# Netscape HTTP Cookie File"]
    for host, path, secure, expiry, name, value in cookies.values():
        fields = (host, "TRUE" if host.startswith(".") else "FALSE", path,
                  "TRUE" if secure else "FALSE", str(expiry), name, value)
        if any("\n" in field or "\r" in field or "\t" in field for field in fields):
            raise RuntimeError("Invalid cookie fields; previous export preserved")
        lines.append("\t".join(fields))
    content = "\n".join(lines) + "\n"
    if output.is_file() and output.read_text() == content:
        return False
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
            os.replace(temporary, output)
        finally:
            temporary.unlink(missing_ok=True)
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        changed = export(args.profile, args.output)
    except Exception:
        raise SystemExit("YouTube cookie sync failed; previous export preserved. Check Firefox profile and session.") from None
    print("YouTube cookies updated" if changed else "YouTube cookies unchanged")
