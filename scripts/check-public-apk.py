#!/usr/bin/env python3
"""Inspect a public self-host APK locally; never echo rejected string values.

DEX layout: https://source.android.com/docs/core/runtime/dex-format
This check complements source and secret scanning, not APK signature verification.
"""

from __future__ import annotations

import argparse
import ipaddress
import os
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path
from urllib.parse import urlsplit


EXPECTED_PACKAGE = "app.homemusic.selfhost"
MAX_SIZE = 500 * 1024 * 1024
MAX_STRING = 4 * 1024 * 1024
EXAMPLES = tuple(ipaddress.ip_network(value) for value in (
    "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "2001:db8::/32",
))
URL = re.compile(r"https?://[^\s<>\x00\"'`]+", re.IGNORECASE)
PROFILE = re.compile(r"(?i)[A-Z]:[\\/]Users[\\/][^\s<>\x00\"']+")
PRIVATE_KEY = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----")


class Rejected(ValueError):
    """A safe diagnostic containing categories, never matching private data."""


def dex_strings(data: bytes):
    """Read bounded legacy DEX string data, including Java modified UTF-8."""
    if len(data) < 112 or len(data) > MAX_SIZE or data[:4] != b"dex\n" or data[4:8] not in (
        b"035\0", b"037\0", b"038\0", b"039\0", b"040\0",
    ):
        raise Rejected("unsupported or malformed DEX header")
    size, header, endian = struct.unpack_from("<III", data, 32)
    count, table = struct.unpack_from("<II", data, 56)
    data_size, data_start = struct.unpack_from("<II", data, 104)
    if size != len(data) or header != 112 or endian != 0x12345678:
        raise Rejected("malformed DEX bounds")
    if data_start < header or data_size > size - data_start or data_start > size:
        raise Rejected("malformed DEX data bounds")
    if count > size // 4 or table < header or table + count * 4 > data_start:
        raise Rejected("malformed DEX string table")
    seen, ranges, processed = set(), [], 0
    for index in range(count):
        offset = struct.unpack_from("<I", data, table + index * 4)[0]
        if offset < data_start or offset >= data_start + data_size or offset in seen:
            raise Rejected("malformed DEX string offset")
        seen.add(offset)
        cursor, units = offset, 0
        for shift in range(0, 35, 7):
            if cursor >= size:
                raise Rejected("truncated DEX string length")
            byte = data[cursor]
            cursor += 1
            if shift == 28 and byte > 15:
                raise Rejected("invalid DEX string length")
            units |= (byte & 127) << shift
            if not byte & 128:
                break
        else:
            raise Rejected("invalid DEX string length")
        end = data.find(b"\0", cursor, min(data_start + data_size, cursor + MAX_STRING + 1))
        if end < 0:
            raise Rejected("unterminated or oversized DEX string")
        processed += end + 1 - offset
        if processed > data_size:
            raise Rejected("overlapping DEX strings")
        try:
            text = data[cursor:end].replace(b"\xc0\x80", b"\0").decode("utf-8", "surrogatepass")
        except UnicodeDecodeError:
            raise Rejected("invalid DEX modified UTF-8") from None
        if len(text) != units or any(ord(char) > 0xFFFF for char in text):
            raise Rejected("invalid DEX UTF-16 length")
        ranges.append((offset, end + 1))
        yield text
    ordered = sorted(ranges)
    if any(a[1] > b[0] for a, b in zip(ordered, ordered[1:])):
        raise Rejected("overlapping DEX strings")


def string_findings(text: str) -> set[str]:
    findings = set()
    if PRIVATE_KEY.search(text):
        findings.add("private key material")
    if PROFILE.search(text):
        findings.add("personal Windows profile path")
    for match in URL.finditer(text):
        try:
            url = urlsplit(match.group())
            if url.username is not None or url.password is not None:
                findings.add("credentials in URL")
            if not url.hostname:
                continue
            try:
                address = ipaddress.ip_address(url.hostname)
            except ValueError:
                continue
            if not (address.is_loopback or address.is_unspecified or address.is_multicast) and not any(
                address in network for network in EXAMPLES if address.version == network.version
            ):
                findings.add("embedded deployment IP URL")
        except ValueError:
            findings.add("malformed URL")
    return findings


def find_aapt(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit)
    else:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        candidates = list((Path(sdk) / "build-tools").glob("*/aapt.exe" if os.name == "nt" else "*/aapt")) if sdk else []
        if not candidates:
            raise Rejected("aapt unavailable; pass --aapt or set ANDROID_HOME")
        path = max(candidates, key=lambda item: tuple(int(v) for v in re.findall(r"\d+", item.parent.name)))
    if not path.is_file():
        raise Rejected("aapt executable unavailable")
    return path


def check_apk(apk: Path, aapt: Path) -> int:
    if not apk.is_file() or apk.stat().st_size > MAX_SIZE:
        raise Rejected("APK unavailable or oversized")
    result = subprocess.run([str(aapt), "dump", "badging", str(apk)], capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=30)
    package = re.search(r"^package: name='([^']+)'", result.stdout, re.MULTILINE)
    if result.returncode or package is None or package.group(1) != EXPECTED_PACKAGE:
        raise Rejected("APK package is not the public self-host client")
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)) or sum(entry.file_size for entry in entries) > MAX_SIZE:
            raise Rejected("duplicate or oversized APK entries")
        dexes = [entry for entry in entries if re.fullmatch(r"classes(?:[2-9]|[1-9]\d+)?\.dex", entry.filename)]
        if "AndroidManifest.xml" not in names or "classes.dex" not in names or any(
            name.endswith(".dex") and not re.fullmatch(r"classes(?:[2-9]|[1-9]\d+)?\.dex", name) for name in names
        ):
            raise Rejected("missing manifest or unexpected DEX layout")
        for entry in dexes:
            try:
                findings = set()
                for text in dex_strings(archive.read(entry)):
                    findings.update(string_findings(text))
            except Rejected as error:
                raise Rejected(f"{entry.filename}: {error}") from None
            if findings:
                raise Rejected(f"{entry.filename}: " + "; ".join(sorted(findings)))
    return len(dexes)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt")
    args = parser.parse_args()
    try:
        count = check_apk(args.apk, find_aapt(args.aapt))
    except (ValueError, OSError, zipfile.BadZipFile, RuntimeError, struct.error, subprocess.SubprocessError):
        # Rejected messages are deliberately safe; exceptions from external tools may contain paths.
        error = sys.exc_info()[1]
        print("Public APK check failed: " + (str(error) if isinstance(error, Rejected) else "cannot inspect APK safely"), file=sys.stderr)
        return 1
    print(f"Public APK check passed ({count} DEX files; package {EXPECTED_PACKAGE}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
