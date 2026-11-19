"""Tests for the Python parts: the F-Droid helper and the installer job.

Run with: python3 -m unittest discover -s tests -p 'test_*.py'
"""

import importlib.util
import json
import os
import pathlib
import sys
import tempfile
import types
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fdroid = load("fdroid", ROOT / "tools" / "fdroid.py")


class FDroidTest(unittest.TestCase):
    def test_resolve_uses_suggested_version(self):
        record = {
            "packageName": "com.aurora.store",
            "suggestedVersionCode": 70,
            "packages": [{"versionName": "4.7.0", "versionCode": 70},
                         {"versionName": "4.8.0-beta", "versionCode": 71}],
        }
        code, url = fdroid.resolve("com.aurora.store", fetch=lambda _: json.dumps(record))
        self.assertEqual(code, 70)
        self.assertEqual(url, "https://f-droid.org/repo/com.aurora.store_70.apk")

    def test_resolve_falls_back_to_newest(self):
        record = {"packageName": "x", "packages": [{"versionCode": 3}, {"versionCode": 9}]}
        self.assertEqual(fdroid.resolve("x", fetch=lambda _: json.dumps(record))[0], 9)

    def test_resolve_rejects_empty_package(self):
        with self.assertRaises(ValueError):
            fdroid.resolve("x", fetch=lambda _: json.dumps({"packageName": "x"}))

    def test_abis(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = os.path.join(tmp, "a.apk")
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("classes.dex", "")
                z.writestr("lib/arm64-v8a/libx.so", "")
                z.writestr("lib/x86_64/libx.so", "")
            self.assertEqual(fdroid.abis(apk), ["arm64-v8a", "x86_64"])

            pure = os.path.join(tmp, "b.apk")
            with zipfile.ZipFile(pure, "w") as z:
                z.writestr("classes.dex", "")
            self.assertEqual(fdroid.abis(pure), ["none"])


class InstallerJobTest(unittest.TestCase):
    """Runs the Calamares job against a fake libcalamares."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        tmp = pathlib.Path(self.tmp.name)
        self.media = tmp / "cdrom"
        self.target = tmp / "target"
        (self.media / "scripts").mkdir(parents=True)
        (self.media / "scripts" / "9-otto").write_text("hook")
        (self.media / "otto" / "user_app").mkdir(parents=True)
        (self.media / "otto" / "init-hooks.sh").write_text("hooks")
        self.target.mkdir()

        storage = {"rootMountPoint": str(self.target)}
        fake = types.ModuleType("libcalamares")
        fake.globalstorage = types.SimpleNamespace(value=storage.get)
        fake.utils = types.SimpleNamespace(debug=lambda *_: None)
        fake.job = types.SimpleNamespace(setprogress=lambda *_: None)
        sys.modules["libcalamares"] = fake
        self.job = load("otto_payload",
                        ROOT / "iso" / "otto" / "installer" / "otto-payload" / "main.py")
        self.job.SOURCE_ROOT = str(self.media)

    def tearDown(self):
        sys.modules.pop("libcalamares", None)
        self.tmp.cleanup()

    def test_copies_payload_to_installed_system(self):
        self.assertIsNone(self.job.run())
        self.assertEqual((self.target / "scripts" / "9-otto").read_text(), "hook")
        self.assertEqual((self.target / "otto" / "init-hooks.sh").read_text(), "hooks")
        # The mount point our initrd hook binds /otto to.
        self.assertTrue((self.target / "boot" / "otto").is_dir())

    def test_reinstall_replaces_old_payload(self):
        (self.target / "otto").mkdir()
        (self.target / "otto" / "stale.txt").write_text("old")
        self.assertIsNone(self.job.run())
        self.assertFalse((self.target / "otto" / "stale.txt").exists())

    def test_missing_payload_is_an_error(self):
        (self.media / "scripts" / "9-otto").unlink()
        (self.media / "scripts").rmdir()
        self.assertIsNotNone(self.job.run())


if __name__ == "__main__":
    unittest.main()
