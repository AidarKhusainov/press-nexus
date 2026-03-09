#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Using Java:"
"$SCRIPT_DIR/use-jdk21.sh" java -version
"$SCRIPT_DIR/use-jdk21.sh" javac -version

echo "Running Maven verify..."
"$SCRIPT_DIR/use-jdk21.sh" ./mvnw -B -ntp clean verify

echo "Local verification completed successfully."
