"""Publication checks against artificial DEX/APK fixtures; no network or real keys."""

import importlib.util
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("public_apk", Path(__file__).resolve().parents[1] / "check-public-apk.py")
public_apk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(public_apk)


def dex_fixture(strings):
    """Minimal string-bearing DEX layout; execution/checksums are irrelevant to this parser."""
    data_start = 112 + 4 * len(strings)
    result = bytearray(data_start)
    result[:8] = b"dex\n035\0"
    struct.pack_into("<II", result, 36, 112, 0x12345678)
    struct.pack_into("<II", result, 56, len(strings), 112)
    for index, text in enumerate(strings):
        struct.pack_into("<I", result, 112 + index * 4, len(result))
        units = len(text)
        while units > 127:
            result.append((units & 127) | 128)
            units >>= 7
        result.append(units)
        result.extend(text.encode("utf-8", "surrogatepass").replace(b"\0", b"\xc0\x80"))
        result.append(0)
    struct.pack_into("<I", result, 32, len(result))
    struct.pack_into("<II", result, 104, len(result) - data_start, data_start)
    return bytes(result)


class PublicApkCheckTest(unittest.TestCase):
    def test_dex_strings_include_modified_utf8_nul_and_surrogate_units(self):
        values = ["http://hub.example.com", "A\0B", "\u00b5", "\ud83d\ude00", "x" * 130]
        self.assertEqual(values, list(public_apk.dex_strings(dex_fixture(values))))

    def test_malformed_dex_never_succeeds(self):
        good = dex_fixture(["example"])
        bad_offset = bytearray(good)
        struct.pack_into("<I", bad_offset, 112, len(good) + 1)
        bad_length = bytearray(good)
        bad_length[116] = 50
        bad_encoding = bytearray(good)
        bad_encoding[117] = 0xFF
        unterminated = bytearray(good)
        unterminated[-1] = 1
        invalid_uleb = bytearray(good)
        invalid_uleb[116:121] = b"\xff" * 5
        for value in (good[:50], b"broken" + good[6:], bytes(bad_offset), bytes(bad_length),
                      bytes(bad_encoding), bytes(unterminated), bytes(invalid_uleb)):
            with self.subTest(length=len(value)):
                with self.assertRaises(public_apk.Rejected):
                    list(public_apk.dex_strings(value))

    def test_private_and_public_deployment_ips_and_url_passwords_are_rejected(self):
        for value in ("http://10.20.30.40:8080", "https://8.8.8.8", "http://[fd00::1]:8080",
                      "https://user:example-password@hub.example.com"):
            with self.subTest(value=value):
                findings = public_apk.string_findings(value)
                self.assertTrue(findings)
                self.assertNotIn(value, " ".join(findings))

    def test_documentation_loopback_multicast_and_dynamic_host_urls_are_allowed(self):
        for value in ("http://203.0.113.10:8080", "http://192.0.2.10", "http://[2001:db8::1]",
                      "http://127.0.0.1:8080", "http://[::1]", "http://239.255.255.250",
                      "https://hub.example.com", "http://%s:%d"):
            with self.subTest(value=value):
                self.assertEqual(set(), public_apk.string_findings(value))

    def test_private_key_and_profile_diagnostics_do_not_echo_the_value(self):
        for value in ("-----BEGIN PRIVATE KEY-----", "C:\\Users\\example-person\\project"):
            reasons = public_apk.string_findings(value)
            self.assertTrue(reasons)
            self.assertNotIn(value, " ".join(reasons))

    def apk_fixture(self, root, strings, extra=None):
        path = root / "example.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"artificial manifest")
            archive.writestr("classes.dex", dex_fixture(strings))
            if extra:
                archive.writestr(*extra)
        return path

    def test_apk_manifest_package_must_be_the_self_host_client(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = self.apk_fixture(Path(folder), ["https://hub.example.com"])
            wrong = subprocess.CompletedProcess([], 0, "package: name='app.homemusic' versionCode='1'", "")
            with patch.object(public_apk.subprocess, "run", return_value=wrong):
                with self.assertRaisesRegex(public_apk.Rejected, "package"):
                    public_apk.check_apk(apk, Path("aapt"))

    def test_apk_checks_each_dex_and_accepts_double_digit_multidex_names(self):
        result = subprocess.CompletedProcess([], 0, "package: name='app.homemusic.selfhost'", "")
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = self.apk_fixture(root, ["https://hub.example.com"], ("classes10.dex", dex_fixture(["http://127.0.0.1"])))
            with patch.object(public_apk.subprocess, "run", return_value=result):
                self.assertEqual(2, public_apk.check_apk(apk, Path("aapt")))
            apk = self.apk_fixture(root, ["https://hub.example.com"], ("classes2.dex", dex_fixture(["http://10.20.30.40"])))
            with patch.object(public_apk.subprocess, "run", return_value=result):
                with self.assertRaisesRegex(public_apk.Rejected, "classes2.dex: embedded deployment IP URL"):
                    public_apk.check_apk(apk, Path("aapt"))

    def test_apk_entry_size_is_bounded_before_decompression(self):
        result = subprocess.CompletedProcess([], 0, "package: name='app.homemusic.selfhost'", "")
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / "app.apk"
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("AndroidManifest.xml", b"synthetic")
                archive.writestr("classes.dex", dex_fixture(["example"]))
                archive.writestr("large.bin", b"x" * 10000)
            self.assertLess(apk.stat().st_size, 2000)
            with patch.object(public_apk.subprocess, "run", return_value=result), patch.object(public_apk, "MAX_SIZE", 2000):
                with self.assertRaisesRegex(public_apk.Rejected, "oversized APK entries"):
                    public_apk.check_apk(apk, Path("aapt"))


if __name__ == "__main__":
    unittest.main()
