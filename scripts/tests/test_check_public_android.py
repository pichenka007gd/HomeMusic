"""Regression checks for accidental publication of private Android inputs."""

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path


spec = importlib.util.spec_from_file_location("public_android", Path(__file__).resolve().parents[1] / "check-public-android.py")
public_android = importlib.util.module_from_spec(spec)
spec.loader.exec_module(public_android)


class PublicationCheckTest(unittest.TestCase):
    def test_upstream_version_tags_and_ssdp_multicast_are_not_deployment_addresses(self):
        self.assertEqual([], public_android.check_file("android/CHANGELOG.md", b"prerelease v4.14.0.1"))
        self.assertEqual([], public_android.check_file("android/app/upnp.kt", b'const val SSDP_HOST = "239.255.255.250"'))

    def test_personal_email_and_bare_deployment_ip_are_rejected(self):
        for text in (b'"private-person@domain.tld"', b'"10.20.30.40:8444"'):
            with self.subTest(text=text):
                self.assertTrue(public_android.check_file("android/app/fixture.kt", text))

    def test_staged_secret_is_not_hidden_by_a_clean_working_file(self):
        # Real Git is necessary here: the publication boundary is the staging area.
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            path = root / "android" / "example.txt"
            path.parent.mkdir()
            path.write_bytes(b'https://user:password@hub.example.com')
            subprocess.run(["git", "-C", str(root), "add", "--", "android"], check=True)
            path.write_bytes(b'https://hub.example.com')
            count, findings = public_android.check_repository(root)
            self.assertEqual(count, 1)
            self.assertTrue(any("credentials in a URL" in item for item in findings))
            self.assertFalse(any("password" in item for item in findings))

    def test_local_signing_inputs_are_rejected_even_if_force_added(self):
        for name in ("android/keystore/release.jks", "android/keystore.properties", "android/homemusic.properties", "android/local.properties", "android/.git"):
            with self.subTest(name=name):
                self.assertTrue(public_android.check_file(name, b"example"))

    def test_configuration_examples_and_source_are_allowed(self):
        for name in ("android/keystore.properties.example", "android/homemusic.properties.example", "android/app/src/main/Example.kt", "android/LICENSE"):
            with self.subTest(name=name):
                self.assertEqual([], public_android.check_file(name, b"https://hub.example.com https://203.0.113.10:8445"))

    def test_live_addresses_and_url_credentials_are_rejected(self):
        for url in (b"http://192.168.1.5:8080", b"https://8.8.8.8", b"https://test-user:example-password@hub.example.com"):
            with self.subTest(url=url):
                self.assertTrue(public_android.check_file("android/example.txt", url))

    def test_key_material_and_profile_paths_are_rejected_without_echoing_values(self):
        for value in (b"-----BEGIN PRIVATE KEY-----", b"C:\\Users\\example-user\\project", b"/home/example-user/project"):
            with self.subTest(value=value):
                reasons = public_android.check_file("android/example.txt", value)
                self.assertTrue(reasons)
                self.assertNotIn(value.decode(), " ".join(reasons))


if __name__ == "__main__":
    unittest.main()
