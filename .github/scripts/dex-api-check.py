#!/usr/bin/env python3
"""Fails when the app's own dex code calls a java.* API newer than minSdk.

Lint misses Java 8 members called on Kotlin's mapped collection types (a removeIf on an
ArrayList, for example), so this reads the built APK instead of the source.
Usage: dex-api-check.py APK API_VERSIONS_XML DEXDUMP MIN_SDK BASELINE [--write-baseline]
"""
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

OWN = "Lcom/andrerinas/"
REF = re.compile(r"\|[0-9a-f]+: (?:invoke-[a-z/-]+|new-instance|new-array|const-class|check-cast|instance-of|[si](?:get|put)[a-z-]*) [^L]*(L(?:java|javax)/[^;]+);(?:\.([^:\s]+):(\S+))?")
METHOD = re.compile(r"\|\[[0-9a-f]+\] (\S+)$")


def level(value):
    # Newer API databases write minor levels such as "37.0"; only the major level matters here.
    return int(str(value).split(".")[0])


def load_api(path):
    classes = {}
    for c in ET.parse(path).getroot().iter("class"):
        since = level(c.get("since", "1"))
        members = {m.get("name"): level(m.get("since", since)) for m in c if m.tag in ("method", "field")}
        supers = [e.get("name") for e in c if e.tag in ("extends", "implements")]
        classes[c.get("name")] = (since, members, supers)
    return classes


def member_since(classes, cls, sig, seen=None):
    seen = seen if seen is not None else set()
    if cls in seen or cls not in classes:
        return None
    seen.add(cls)
    _, members, supers = classes[cls]
    if sig in members:
        return members[sig]
    found = [s for s in (member_since(classes, p, sig, seen) for p in supers) if s is not None]
    return min(found) if found else None


def scan(apk, dexdump):
    hits = set()
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for name in sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)):
            path = z.extract(name, tmp)
            out = subprocess.run([dexdump, "-d", path], capture_output=True, check=True).stdout.decode("utf-8", "replace")
            caller = None
            for line in out.splitlines():
                m = METHOD.search(line)
                if m:
                    caller = m.group(1)
                    continue
                if caller and caller.startswith(OWN[1:].replace("/", ".")):
                    i = REF.search(line)
                    if i:
                        name, desc = i.group(2) or "", i.group(3) or ""
                        sig = name + desc if desc.startswith("(") else name
                        hits.add((caller, i.group(1)[1:], sig))
    return hits


def main():
    apk, api_xml, dexdump, min_sdk, baseline = sys.argv[1:6]
    min_sdk = int(min_sdk)
    classes = load_api(api_xml)
    found = []
    for caller, cls, sig in sorted(scan(apk, dexdump)):
        if cls not in classes:
            continue
        # A member is usable only when its receiver class exists too.
        since = max(classes[cls][0], (member_since(classes, cls, sig) or 0) if sig else 0)
        if since > min_sdk:
            target = f"{cls}.{sig}" if sig else cls
            found.append(f"API {since}: {caller} -> {target}")
    if "--write-baseline" in sys.argv:
        with open(baseline, "w") as f:
            f.write("\n".join(found) + "\n")
        print(f"Wrote {len(found)} entries to {baseline}")
        return 0
    try:
        known = set(open(baseline).read().splitlines())
    except FileNotFoundError:
        known = set()
    new = [f for f in found if f not in known]
    print(f"{len(found)} java.* calls above API {min_sdk}; {len(found) - len(new)} in the baseline.")
    for f in new:
        print(f"::error::{f}")
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
