#!/usr/bin/env python3
"""Small helpers for fetching apps from the official F-Droid repository.

  fdroid.py resolve <package>   prints "<versionCode> <apk url>" for the
                                version F-Droid currently suggests
  fdroid.py abis <file.apk>     prints the native ABIs the APK ships
                                ("none" when it is pure Java/Kotlin)

Note: F-Droid documents the v1 packages API as meant for display, and
recommends the signed repo index for fetching apps. We only use it to pick a
version code and download over HTTPS from f-droid.org; moving to the signed
index (verified against F-Droid's pinned key) is on the roadmap.
"""

import json
import sys
import urllib.request
import zipfile

API = "https://f-droid.org/api/v1/packages/{}"
REPO = "https://f-droid.org/repo/{}_{}.apk"


def pick_version(info):
    """Version code to download from an API v1 package record."""
    code = info.get("suggestedVersionCode")
    if code is None:
        versions = info.get("packages") or []
        if not versions:
            raise ValueError("no versions published for {}".format(info.get("packageName")))
        code = max(v["versionCode"] for v in versions)
    return int(code)


def resolve(package, fetch=None):
    if fetch is None:
        def fetch(url):
            with urllib.request.urlopen(url, timeout=30) as response:
                return response.read()
    info = json.loads(fetch(API.format(package)))
    code = pick_version(info)
    return code, REPO.format(package, code)


def abis(apk_path):
    with zipfile.ZipFile(apk_path) as apk:
        found = sorted({
            name.split("/")[1]
            for name in apk.namelist()
            if name.startswith("lib/") and name.count("/") >= 2
        })
    return found or ["none"]


def main(argv):
    if len(argv) == 3 and argv[1] == "resolve":
        code, url = resolve(argv[2])
        print(code, url)
    elif len(argv) == 3 and argv[1] == "abis":
        print(" ".join(abis(argv[2])))
    else:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
