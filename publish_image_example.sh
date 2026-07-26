#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_directory"

exec ./kt/gradlew -p kt dockerPublishImage \
    -PdockerImageTag=kotlin-vue \
    -PdockerPublicHost=your-host.example
