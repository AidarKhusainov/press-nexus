#!/usr/bin/env bash
set -euo pipefail

JDK_CANDIDATES=(
  "/home/aidar/.jdks/corretto-21.0.4"
  "/usr/lib/jvm/java-21-openjdk-amd64"
  "/usr/lib/jvm/default-java"
)

pick_jdk21() {
  local candidate
  for candidate in "${JDK_CANDIDATES[@]}"; do
    if [[ -x "$candidate/bin/java" && -x "$candidate/bin/javac" ]]; then
      if "$candidate/bin/java" -version 2>&1 | grep -q 'version "21'; then
        echo "$candidate"
        return 0
      fi
    fi
  done
  return 1
}

if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" && -x "${JAVA_HOME}/bin/javac" ]]; then
  if ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q 'version "21'; then
    JAVA_HOME="$(pick_jdk21 || true)"
  fi
else
  JAVA_HOME="$(pick_jdk21 || true)"
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "ERROR: JDK 21 not found." >&2
  echo "Checked: ${JDK_CANDIDATES[*]}" >&2
  exit 1
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [[ $# -eq 0 ]]; then
  java -version
  javac -version
  exit 0
fi

exec "$@"
