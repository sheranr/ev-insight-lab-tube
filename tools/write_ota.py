#!/usr/bin/env python3
"""Write docs/ota/version.json, the file installed apps read to find updates.

Run by .github/workflows/release.yml after a release APK is built. Can also be run by
hand, e.g. to force an update with --min-supported (users below it cannot skip).
"""
import argparse
import json
import pathlib
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "ota" / "version.json"


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--name", required=True, help="versionName, e.g. 1.0.1")
    p.add_argument("--code", required=True, type=int, help="versionCode, e.g. 11")
    p.add_argument("--sha256", required=True)
    p.add_argument("--size", required=True, type=int)
    p.add_argument("--url", required=True, help="direct APK download URL")
    p.add_argument("--notes-file", default="release-notes.txt")
    p.add_argument("--min-supported", type=int, default=None,
                   help="versionCode below which the update is mandatory")
    a = p.parse_args()

    old = {}
    if OUT.exists():
        try:
            old = json.loads(OUT.read_text())
        except ValueError:
            old = {}

    notes_path = ROOT / a.notes_file
    notes = notes_path.read_text().strip() if notes_path.exists() else ""

    data = {
        "app": "ev-insight-lab-tube",
        "versionCode": a.code,
        "versionName": a.name,
        "apkUrl": a.url,
        "sha256": a.sha256.lower(),
        "size": a.size,
        "notes": notes,
        "minSupportedCode": a.min_supported if a.min_supported is not None else old.get("minSupportedCode", 0),
        "released": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, indent=2) + "\n")
    print(json.dumps(data, indent=2))


if __name__ == "__main__":
    main()
