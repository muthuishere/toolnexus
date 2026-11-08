#!/usr/bin/env bash
# CI only: fetch the Gradle distribution from Gradle's official GitHub mirror
# (gradle/gradle-distributions) instead of services.gradle.org, which has
# returned 504s and failed whole runs. The wrapper is pointed at the local zip,
# and gradle-wrapper.properties pins distributionSha256Sum, so a wrong file fails
# loudly. Needs GH_TOKEN. Run from java/.
set -euo pipefail
props=gradle/wrapper/gradle-wrapper.properties
v=$(sed -n 's/^distributionUrl=.*gradle-\(.*\)-bin\.zip$/\1/p' "$props")
dir="${RUNNER_TEMP:-/tmp}/gradle-dist"
mkdir -p "$dir"
for i in 1 2 3; do
  gh release download "v$v" -R gradle/gradle-distributions -p "gradle-$v-bin.zip" -D "$dir" --clobber && break
  sleep $((i * 10))
done
sed -i.bak "s#^distributionUrl=.*#distributionUrl=file\\\\://$dir/gradle-$v-bin.zip#" "$props" && rm -f "$props.bak"
echo "gradle $v from GitHub mirror: $(grep '^distributionUrl' "$props")"
