"""Build one lean POSIX-path PlowTAK zip per ATAK line for tak.gov user_builds.

TPP runs ./gradlew assembleCivRelease with no -P, so each zip pins the line
and a shared version stamp in gradle.properties:

    PLUGIN_VERSION=yy.MMdd.HHmm
    atak.version=5.8.0

Both keys are project properties, which app/build.gradle already reads.

    python scripts/build-tpp-zips.py
    python scripts/build-tpp-zips.py 5.8.0 --version 26.1019.1407

Writes dist/tpp/PlowTAK-<stamp>-ATAK-<line>-user-build.zip
"""
from __future__ import annotations

import argparse
import os
import shutil
import stat
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
PLUGIN = "PlowTAK"
LINES = ("5.6.0", "5.7.0", "5.8.0")

REQUIRED_FILES = [
    "build.gradle",
    "gradle.properties",
    "settings.gradle",
    "gradlew",
    "gradlew.bat",
    "README.md",
    "template.local.properties",
    "app/build.gradle",
    "app/proguard-gradle.txt",
    "app/src/main/AndroidManifest.xml",
]
REQUIRED_DIRS = [
    "gradle/wrapper",
    "app/src/main/java",
    "app/src/main/res",
    "app/src/main/assets",
]
OPTIONAL_FILES = [
    "app/proguard-gradle-repackage.txt",
    "CHANGELOG.md",
    "LICENSE",
]
OPTIONAL_DIRS = [
    "app/src/test",
    "app/src/gov",
    "app/src/civ",
    "gradle",
]


def copy_file(stage: Path, rel: str) -> None:
    src = REPO / rel
    if not src.is_file():
        raise SystemExit(f"missing required file: {rel}")
    dest = stage / rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)


def copy_tree(stage: Path, rel: str, required: bool) -> None:
    src = REPO / rel
    if not src.is_dir():
        if required:
            raise SystemExit(f"missing required directory: {rel}")
        return
    for path in src.rglob("*"):
        if not path.is_file():
            continue
        if path.name in {".DS_Store", "Thumbs.db"}:
            continue
        if path.suffix.lower() in {".jks", ".keystore", ".apk", ".aab"}:
            continue
        rel_path = path.relative_to(REPO)
        dest = stage / rel_path
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)


def stage_tree(stage: Path) -> None:
    if stage.exists():
        shutil.rmtree(stage, ignore_errors=True)
    if stage.exists():
        empty = REPO / "dist" / "tpp" / "_empty_del"
        empty.mkdir(parents=True, exist_ok=True)
        os.system(
            f'robocopy "{empty}" "{stage}" /MIR /NFL /NDL /NJH /NJS /nc /ns /np >nul'
        )
        shutil.rmtree(stage, ignore_errors=True)
        shutil.rmtree(empty, ignore_errors=True)
    stage.mkdir(parents=True)

    for rel in REQUIRED_FILES:
        copy_file(stage, rel)
    for rel in REQUIRED_DIRS:
        copy_tree(stage, rel, required=True)
    for rel in OPTIONAL_FILES:
        if (REPO / rel).is_file():
            copy_file(stage, rel)
    for rel in OPTIONAL_DIRS:
        if rel == "gradle":
            gdir = REPO / "gradle"
            if gdir.is_dir():
                for path in gdir.glob("*.gradle"):
                    dest = stage / "gradle" / path.name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(path, dest)
            continue
        copy_tree(stage, rel, required=False)


def pin_gradle_properties(stage: Path, version: str, line: str) -> None:
    props = stage / "gradle.properties"
    text = props.read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n")
    if not text.endswith("\n"):
        text += "\n"
    text += (
        "\n"
        "# Pinned by scripts/build-tpp-zips.py. TPP does not pass -P.\n"
        f"PLUGIN_VERSION={version}\n"
        f"atak.version={line}\n"
    )
    props.write_text(text, encoding="utf-8", newline="\n")


def zip_stage(stage: Path, out: Path) -> None:
    if out.exists():
        out.unlink()
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(stage.rglob("*")):
            if not path.is_file():
                continue
            arc = f"{PLUGIN}/" + path.relative_to(stage).as_posix()
            info = zipfile.ZipInfo(
                arc, date_time=time.localtime(path.stat().st_mtime)[:6]
            )
            info.compress_type = zipfile.ZIP_DEFLATED
            mode = 0o755 if path.name == "gradlew" else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            zf.writestr(info, path.read_bytes())

    with zipfile.ZipFile(out) as zf:
        names = zf.namelist()
        bad = [n for n in names if "\\" in n]
        bad_root = [n for n in names if not n.startswith(f"{PLUGIN}/")]
        has_wrapper = any(n.endswith("gradle/wrapper/gradle-wrapper.jar") for n in names)
        has_libs = any("/libs/" in n for n in names)
        has_takdev = any(".takdev/" in n or n.endswith(".takdev") for n in names)
        has_docs = any("/docs/" in n for n in names)
        has_secrets = any(
            n.endswith("/local.properties") or n.endswith(".jks") or n.endswith(".keystore")
            for n in names
        )
        proguard = next((n for n in names if n.endswith("proguard-gradle.txt")), None)
        apply_ok = False
        if proguard:
            text = zf.read(proguard).decode("utf-8", "replace")
            apply_ok = "-applymapping" in text and "atak.proguard.mapping" in text
        props = zf.read(f"{PLUGIN}/gradle.properties").decode("utf-8")
        size = out.stat().st_size
        print(f"zip={out}")
        print(f"size={size} ({size / 1024:.1f} KB)")
        print(
            f"entries={len(names)} bad_backslash={len(bad)} bad_root={len(bad_root)} "
            f"wrapper={has_wrapper} applymapping={apply_ok}"
        )
        if bad or bad_root:
            raise SystemExit("zip path layout invalid")
        if not has_wrapper:
            raise SystemExit("gradle-wrapper.jar missing — TPP cannot bootstrap Gradle")
        if has_libs or has_takdev or has_docs:
            raise SystemExit("zip still contains libs/, .takdev/, or docs/")
        if has_secrets:
            raise SystemExit("zip contains secrets (local.properties / keystore)")
        if not apply_ok:
            raise SystemExit("proguard-gradle.txt missing applymapping")
        if "PLUGIN_VERSION=" not in props or "atak.version=" not in props:
            raise SystemExit("gradle.properties missing PLUGIN_VERSION or atak.version")
        if size > 2_000_000:
            print(
                "WARNING: zip > 2 MB — expected lean source zip is usually < 1 MB. "
                "Check for accidental binaries."
            )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lines", nargs="*", default=list(LINES))
    parser.add_argument(
        "--version",
        help="yy.MMdd.HHmm UTC stamp shared by every zip (default: now)",
    )
    args = parser.parse_args()
    lines = args.lines or list(LINES)
    unknown = [line for line in lines if line not in LINES]
    if unknown:
        raise SystemExit(f"unsupported ATAK line: {', '.join(unknown)}")
    version = args.version or datetime.now(timezone.utc).strftime("%y.%m%d.%H%M")
    if len(version.split(".")) != 3:
        raise SystemExit(f"version must look like yy.MMdd.HHmm, got {version}")

    base = REPO / "dist" / "tpp" / "stage"
    stage_tree(base)
    out_dir = REPO / "dist" / "tpp"
    for line in lines:
        line_stage = REPO / "dist" / "tpp" / f"stage-{line}"
        if line_stage.exists():
            shutil.rmtree(line_stage, ignore_errors=True)
        shutil.copytree(base, line_stage)
        pin_gradle_properties(line_stage, version, line)
        out = out_dir / f"PlowTAK-{version}-ATAK-{line}-user-build.zip"
        zip_stage(line_stage, out)
        shutil.rmtree(line_stage, ignore_errors=True)
        print(f"expected apk=PlowTAK-{version}-ATAK-{line}-civ-release.apk")
    shutil.rmtree(base, ignore_errors=True)


if __name__ == "__main__":
    main()
