#!/usr/bin/env python3
"""Check the tracked Android snapshot for local files and deployment details.

Run from any directory. This complements a secret scanner; it does not read ignored
local configuration or contact the configured music server.
"""

from __future__ import annotations

import ipaddress
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit


EXCLUDED_PARTS = {
    ".git", ".gradle", ".idea", ".kotlin", ".claude", ".agents", ".codex",
    "build", "keystore", "captures", "graphify-out",
}
LOCAL_FILES = {"local.properties", "keystore.properties", "homemusic.properties", ".env", "CLAUDE.md", "AGENTS.md"}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".apk", ".aab", ".log", ".db", ".sqlite", ".sqlite3"}
EXAMPLE_NETWORKS = tuple(ipaddress.ip_network(net) for net in (
    "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "2001:db8::/32",
))
URL = re.compile(r"https?://[^\s<>\"'`)]+")
IPV4 = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
EMAIL = re.compile(r"(?<![\w.+-])[\w.%+-]+@([\w.-]+\.[A-Za-z]{2,})")
PROFILE_PATH = re.compile(r"(?i)(?:[A-Z]:[\\/]Users[\\/]|/Users/|/home/)[^\s<>\"']+")
PRIVATE_KEY = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----")


def check_file(name: str, data: bytes) -> list[str]:
    """Return reasons, never secret values or matching source lines."""
    path = PurePosixPath(name)
    errors = []
    if any(part in EXCLUDED_PARTS for part in path.parts[1:]) or path.name in LOCAL_FILES or path.name.startswith(".env."):
        errors.append("local configuration, metadata or build output")
    if path.suffix.lower() in PRIVATE_SUFFIXES:
        errors.append("key, database, log or compiled application")
    if b"\0" in data[:8192]:
        return errors
    text = data.decode("utf-8", errors="replace")
    if PRIVATE_KEY.search(text):
        errors.append("private key material")
    if PROFILE_PATH.search(text):
        errors.append("personal profile path")
    for match in EMAIL.finditer(text):
        # Kotlin's this@Class.property is a qualified expression, not an email.
        if match.group().startswith("this@"):
            continue
        domain = match.group(1).lower()
        if domain not in {"example.com", "example.org", "example.net", "github.com", "users.noreply.github.com"} and not domain.endswith((".example", ".invalid", ".test")):
            errors.append("personal email; use an example or GitHub noreply address")
    for match in IPV4.finditer(text):
        try:
            address = ipaddress.ip_address(match.group())
        except ValueError:
            continue
        if not (address.is_loopback or address.is_unspecified or address.is_multicast) and not any(address in net for net in EXAMPLE_NETWORKS if address.version == net.version):
            errors.append("deployment IP; use a documentation address")
    for match in URL.finditer(text):
        try:
            parsed = urlsplit(match.group())
            if parsed.username is not None or parsed.password is not None:
                errors.append("credentials in a URL")
            if not parsed.hostname:
                continue
            try:
                address = ipaddress.ip_address(parsed.hostname)
            except ValueError:
                continue
            if not (address.is_loopback or address.is_unspecified or address.is_multicast) and not any(address in net for net in EXAMPLE_NETWORKS if address.version == net.version):
                errors.append("deployment IP in a URL; use a documentation address")
        except ValueError:
            errors.append("malformed URL")
    return sorted(set(errors))


def check_repository(root: Path) -> tuple[int, list[str]]:
    """Inspect staged blobs, including content hidden by later working-tree edits."""
    rows = subprocess.check_output(["git", "-C", str(root), "ls-files", "--stage", "-z", "--", "android"]).split(b"\0")
    count = 0
    findings = []
    with subprocess.Popen(["git", "-C", str(root), "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE) as objects:
        for row in rows:
            if not row:
                continue
            metadata, name_bytes = row.split(b"\t", 1)
            mode, oid, stage = metadata.split()
            name = name_bytes.decode("utf-8")
            count += 1
            if mode == b"160000" or stage != b"0":
                findings.append(f"{name}: nested Git repository or unresolved conflict")
                continue
            objects.stdin.write(oid + b"\n")
            objects.stdin.flush()
            header = objects.stdout.readline().split()
            data = objects.stdout.read(int(header[2]))
            objects.stdout.read(1)
            if mode == b"120000":
                # Preserve the single upstream locale alias, without trusting local symlinks.
                if name != "android/app/src/main/res/values-zh-rCN" or data != b"values-zh":
                    findings.append(f"{name}: unexpected symlink in public sources")
                continue
            findings.extend(f"{name}: {reason}" for reason in check_file(name, data))
        objects.stdin.close()
    if not count:
        findings.append("No tracked Android sources found; stage the snapshot before checking it.")
    return count, findings


def main() -> int:
    count, findings = check_repository(Path(__file__).resolve().parents[1])
    if findings:
        print("Public Android source check failed:", file=sys.stderr)
        print("\n".join(findings), file=sys.stderr)
        return 1
    print(f"Public Android source check passed ({count} tracked files).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
