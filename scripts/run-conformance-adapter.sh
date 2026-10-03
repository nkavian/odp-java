#!/bin/sh
set -eu

classpath_file=$(mktemp)
trap 'rm -f "$classpath_file"' EXIT INT TERM

./mvnw --quiet --batch-mode --no-transfer-progress \
  -f tools/odp-conformance/pom.xml \
  -DincludeScope=runtime \
  -Dmdep.outputFile="$classpath_file" \
  dependency:build-classpath < /dev/null >&2
classpath=$(cat "$classpath_file")
rm -f "$classpath_file"
trap - EXIT INT TERM

exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" \
  -cp "tools/odp-conformance/target/classes:$classpath" \
  org.offeringprotocol.odp.conformance.ConformanceAdapter
