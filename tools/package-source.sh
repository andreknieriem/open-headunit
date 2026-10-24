#!/usr/bin/env bash
# Run before building: publish the exact checked-out source beside its APK.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
    echo "Refusing to package HEAD as matching source for a modified working tree." >&2
    exit 1
fi
if git ls-files --stage | grep '^160000 ' > /dev/null; then
    echo "Submodule sources must be included explicitly before distributing this archive." >&2
    exit 1
fi
mkdir -p build/source
revision="$(git rev-parse HEAD)"
git archive --format=tar.gz --prefix=open-headunit/ \
    --output=build/source/open-headunit-source.tar.gz "$revision"
printf '%s\n' "$revision" > build/source/SOURCE_REVISION.txt
(cd build/source && sha256sum open-headunit-source.tar.gz > SOURCE_SHA256.txt)
echo "Source archived at revision $revision"
