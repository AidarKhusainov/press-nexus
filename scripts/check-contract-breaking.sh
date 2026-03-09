#!/usr/bin/env bash
set -euo pipefail

BASE_REF="${CONTRACT_BASE_REF:-origin/main}"
RANGE="${BASE_REF}...HEAD"

if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
	echo "Contract check skipped: base ref '$BASE_REF' not found."
	exit 0
fi

has_removed_lines() {
	local file="$1"
	git diff --unified=0 "$RANGE" -- "$file" | awk '
		/^--- / {next}
		/^\+\+\+ / {next}
		/^@@/ {next}
		/^-/ {removed=1}
		END {exit removed ? 0 : 1}
	'
}

extract_openapi_version() {
	local file="$1"
	awk '
		/^info:[[:space:]]*$/ {in_info=1; next}
		in_info && /^[^[:space:]]/ {in_info=0}
		in_info && /^[[:space:]]+version:[[:space:]]*/ {
			sub(/^[[:space:]]+version:[[:space:]]*/, "", $0)
			gsub(/"/, "", $0)
			print $0
			exit
		}
	' "$file"
}

major_from_semver() {
	local version="$1"
	echo "$version" | sed -E 's/^v?([0-9]+).*/\1/'
}

mapfile -t changed_contract_files < <(git diff --name-only "$RANGE" -- docs/contracts)
if [[ ${#changed_contract_files[@]} -eq 0 ]]; then
	echo "No contract changes detected."
	exit 0
fi

echo "Checking contracts against $BASE_REF"

failures=0

if git diff --name-only "$RANGE" -- docs/contracts/openapi.yaml | grep -q .; then
	if has_removed_lines "docs/contracts/openapi.yaml"; then
		base_openapi="$(mktemp)"
		trap 'rm -f "$base_openapi"' EXIT

		if git show "${BASE_REF}:docs/contracts/openapi.yaml" >"$base_openapi" 2>/dev/null; then
			base_version="$(extract_openapi_version "$base_openapi")"
			head_version="$(extract_openapi_version "docs/contracts/openapi.yaml")"
			base_major="$(major_from_semver "$base_version")"
			head_major="$(major_from_semver "$head_version")"

			if [[ -z "$base_major" || -z "$head_major" ]]; then
				echo "ERROR: unable to parse OpenAPI version (base='$base_version', head='$head_version')."
				failures=$((failures + 1))
			elif (( head_major <= base_major )); then
				echo "ERROR: Potentially breaking OpenAPI change detected but major version was not bumped."
				echo "Base info.version=$base_version, head info.version=$head_version"
				failures=$((failures + 1))
			else
				echo "OpenAPI major version bump detected: $base_version -> $head_version"
			fi
		else
			echo "OpenAPI added in this branch; no base file to compare."
		fi
	else
		echo "OpenAPI changes are additive-only."
	fi
fi

mapfile -t event_added_files < <(git diff --name-status "$RANGE" -- 'docs/contracts/events/*.schema.json' | awk '$1 == "A" {print $2}')
mapfile -t event_changed_files < <(git diff --name-only "$RANGE" -- 'docs/contracts/events/*.schema.json')

for file in "${event_changed_files[@]}"; do
	if ! git cat-file -e "${BASE_REF}:${file}" 2>/dev/null; then
		continue
	fi

	if ! has_removed_lines "$file"; then
		continue
	fi

	base_name="$(basename "$file")"
	if [[ ! "$base_name" =~ ^(.+)-v([0-9]+)\.schema\.json$ ]]; then
		echo "ERROR: Event schema must be versioned with -vN suffix: $base_name"
		failures=$((failures + 1))
		continue
	fi

	prefix="${BASH_REMATCH[1]}"
	current_version="${BASH_REMATCH[2]}"
	found_newer=0

	for added in "${event_added_files[@]}"; do
		added_name="$(basename "$added")"
		if [[ "$added_name" =~ ^${prefix}-v([0-9]+)\.schema\.json$ ]]; then
			added_version="${BASH_REMATCH[1]}"
			if (( added_version > current_version )); then
				found_newer=1
				break
			fi
		fi
	done

	if (( found_newer == 0 )); then
		echo "ERROR: Potentially breaking event schema change in $base_name without version bump."
		echo "Add a new schema file with incremented version (for example ${prefix}-v$((current_version + 1)).schema.json)."
		failures=$((failures + 1))
	else
		echo "Event schema bump detected for $prefix (v$current_version -> newer)."
	fi
done

if (( failures > 0 )); then
	echo "Contract breaking check failed with $failures violation(s)."
	exit 1
fi

echo "Contract breaking check passed."
