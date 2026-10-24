#!/usr/bin/env python3
"""Verify the notices in an actual APK, including verbatim dependency license texts."""
from pathlib import Path
import argparse
import zipfile

ROOT = Path(__file__).resolve().parents[4]
MANIFEST = ROOT / "app/src/main/cpp/third_party/audio-notice-files.txt"
REQUIRED = {
    "NOTICE_AUDIO.txt",
    "docs/AUDIO_SOURCE.md",
    "app/src/main/cpp/third_party/README.md",
    "app/src/main/cpp/third_party/soundtouch/COPYING.TXT",
    "app/src/main/cpp/third_party/rubberband/COPYING",
    "app/src/main/cpp/third_party/GPL-3.0.txt",
    "app/src/main/cpp/third_party/rubberband/src/ext/kissfft/COPYING",
    "app/src/main/cpp/third_party/rubberband/src/ext/speex/COPYING",
}


def expected_notices():
    paths = [line for line in MANIFEST.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not REQUIRED.issubset(paths) or len(paths) != len(set(paths)):
        raise ValueError("Missing or duplicate audio notice input")
    # read_bytes preserves upstream CRLF and form-feed characters, like Gradle readText.
    return "\n\n".join(
        f"===== {path} =====\n\n" + (ROOT / path).read_bytes().decode("utf-8")
        for path in paths
    ).encode("utf-8")


def verify(apk):
    with zipfile.ZipFile(apk) as package:
        if package.read("assets/audio-third-party.txt") != expected_notices():
            raise ValueError(f"{apk}: audio notices are incomplete or stale")
        if package.read("assets/LICENSE") != (ROOT / "LICENSE").read_bytes():
            raise ValueError(f"{apk}: project license changed or missing")
    print(f"PASS {apk}: project license, audio notices, license texts and source guide")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="+")
    for apk in parser.parse_args().apk:
        verify(apk)
