#!/usr/bin/env sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_root"
# The adjacent toolchain is a convenience for this workspace, not a build requirement.
shared_tools="$project_root/../Kobby_SocialMessenger/.tools"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$shared_tools/jdk-21" ]; then
    export JAVA_HOME="$shared_tools/jdk-21"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$shared_tools/android-sdk" ]; then
    export ANDROID_HOME="$shared_tools/android-sdk"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$project_root/.cache/gradle}"
if [ "$#" -eq 0 ]; then set -- testDebugUnitTest lintDebug assembleDebug; fi
exec ./gradlew --no-daemon "$@"
