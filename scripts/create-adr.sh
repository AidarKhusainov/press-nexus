#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <number> <short-title>"
  echo "Example: $0 0002 queue-broker-externalization"
  exit 1
fi

number="$1"
shift
title="$*"

file="docs/adr/${number}-${title}.md"
if [[ -f "$file" ]]; then
  echo "ERROR: $file already exists"
  exit 1
fi

cat > "$file" <<TPL
# ADR ${number}: ${title}

- Status: Proposed
- Date: $(date +%F)
- Deciders: Press Nexus maintainers

## Context

## Decision

## Alternatives Considered

- Option A
- Option B

## Consequences

- Positive
- Negative
- Risks and mitigations

## Follow-up

TPL

echo "Created: $file"
