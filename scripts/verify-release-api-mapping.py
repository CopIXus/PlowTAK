#!/usr/bin/env python3
"""Fail if a release plugin APK still references unmapped SDK API names.

Release ATAK-CIV obfuscates gov.tak.api.plugin.IServiceController ->
gov.tak.api.plugin.a. Plugins must apply <atak.proguard.mapping> so the
dex uses the host names. Usage:

  python scripts/verify-release-api-mapping.py path/to/plugin.apk
"""
from __future__ import annotations

import sys
import zipfile
from pathlib import Path


def check(apk: Path) -> int:
    with zipfile.ZipFile(apk) as zf:
        data = zf.read("classes.dex")
    isc = data.count(b"IServiceController")
    mapped = data.count(b"Lgov/tak/api/plugin/a;")
    print(f"{apk}: IServiceController={isc} Lgov/tak/api/plugin/a;={mapped}")
    if isc:
        print(
            "FAIL: release APK still references IServiceController. "
            "Ensure app/proguard-gradle.txt has "
            "'-applymapping <atak.proguard.mapping>' and the build used "
            "Maven/TPP mapping (offline SDK mapping.txt is empty)."
        )
        return 1
    if mapped == 0:
        print(
            "WARN: no Lgov/tak/api/plugin/a; found — plugin may not "
            "reference IServiceController at all; verify Load on device."
        )
    else:
        print("OK: release API mapping looks applied.")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    return check(Path(argv[1]))


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
