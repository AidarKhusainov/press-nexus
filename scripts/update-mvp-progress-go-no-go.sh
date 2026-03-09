#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_FILE="$REPO_ROOT/docs/MVP_PROGRESS.md"

API_BASE_URL="http://localhost:8080"
REPORT_DATE="$(date -d "yesterday" +%F)"
REPORT_JSON_FILE=""
DRY_RUN="false"
TODAY_OVERRIDE=""
CURL_MAX_TIME_SECONDS="${CURL_MAX_TIME_SECONDS:-10}"
CURL_RETRY_COUNT="${CURL_RETRY_COUNT:-3}"
CURL_RETRY_DELAY_SECONDS="${CURL_RETRY_DELAY_SECONDS:-1}"

usage() {
	cat <<'EOF'
Usage: ./scripts/update-mvp-progress-go-no-go.sh [options]

Options:
  --date YYYY-MM-DD          Report date for /api/analytics/product-report/daily (default: yesterday)
  --api-base-url URL         API base URL (default: http://localhost:8080)
  --report-json-file PATH    Read ProductDailyReport JSON from file instead of HTTP call
  --target-file PATH         Override docs/MVP_PROGRESS.md target path
  --today YYYY-MM-DD         Override "Last updated" value (default: current day)
  --dry-run                  Print diff without modifying docs/MVP_PROGRESS.md
  -h, --help                 Show help
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--date)
			REPORT_DATE="${2:-}"
			shift 2
			;;
		--api-base-url)
			API_BASE_URL="${2:-}"
			shift 2
			;;
		--report-json-file)
			REPORT_JSON_FILE="${2:-}"
			shift 2
			;;
		--target-file)
			TARGET_FILE="${2:-}"
			shift 2
			;;
		--today)
			TODAY_OVERRIDE="${2:-}"
			shift 2
			;;
		--dry-run)
			DRY_RUN="true"
			shift
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			echo "Unknown option: $1" >&2
			usage
			exit 1
			;;
	esac
done

if ! command -v jq >/dev/null 2>&1; then
	echo "jq is required but not found in PATH." >&2
	exit 1
fi

if [[ ! -f "$TARGET_FILE" ]]; then
	echo "Target file not found: $TARGET_FILE" >&2
	exit 1
fi

if [[ -n "$REPORT_JSON_FILE" ]]; then
	if [[ ! -f "$REPORT_JSON_FILE" ]]; then
		echo "Report JSON file not found: $REPORT_JSON_FILE" >&2
		exit 1
	fi
	REPORT_JSON="$(cat "$REPORT_JSON_FILE")"
else
	REPORT_JSON="$(curl --fail --show-error --silent \
		--connect-timeout "$CURL_MAX_TIME_SECONDS" \
		--max-time "$CURL_MAX_TIME_SECONDS" \
		--retry "$CURL_RETRY_COUNT" \
		--retry-delay "$CURL_RETRY_DELAY_SECONDS" \
		--retry-all-errors \
		"$API_BASE_URL/api/analytics/product-report/daily?date=$REPORT_DATE")"
fi

if ! jq -e . >/dev/null 2>&1 <<<"$REPORT_JSON"; then
	echo "Invalid JSON payload from report source." >&2
	exit 1
fi

number_or_zero() {
	local path="$1"
	jq -r "$path // 0" <<<"$REPORT_JSON"
}

format_pct() {
	local value="$1"
	awk -v v="$value" 'BEGIN { printf "%.1f", (v + 0) }'
}

is_ge() {
	local value="$1"
	local threshold="$2"
	awk -v v="$value" -v t="$threshold" 'BEGIN { exit !(v + 0 >= t + 0) }'
}

is_le() {
	local value="$1"
	local threshold="$2"
	awk -v v="$value" -v t="$threshold" 'BEGIN { exit !(v + 0 <= t + 0) }'
}

report_display_date="$(jq -r '.reportDate // empty' <<<"$REPORT_JSON")"
if [[ -z "$report_display_date" || "$report_display_date" == "null" ]]; then
	report_display_date="$REPORT_DATE"
fi

d7_retention_pct="$(number_or_zero '.d7RetentionPct')"
d7_retained_users="$(number_or_zero '.d7RetainedUsers')"
d7_cohort_size="$(number_or_zero '.d7CohortSize')"

useful_rate_pct="$(number_or_zero '.usefulRatePct')"
useful_count="$(number_or_zero '.usefulCount')"
noise_rate_pct="$(number_or_zero '.noiseRatePct')"
noise_count="$(number_or_zero '.noiseCount')"
anxious_count="$(number_or_zero '.anxiousCount')"
quality_feedback_base="$((useful_count + noise_count + anxious_count))"

premium_intent_pct="$(number_or_zero '.premiumIntentPct')"
premium_intent_users="$(number_or_zero '.premiumIntentUsers')"
delivery_users="$(number_or_zero '.deliveryUsers')"

if (( d7_cohort_size > 0 )); then
	d7_current="$(format_pct "$d7_retention_pct")% (${d7_retained_users}/${d7_cohort_size}, ${report_display_date})"
	if is_ge "$d7_retention_pct" "35"; then
		d7_status="DONE"
	else
		d7_status="TODO"
	fi
else
	d7_current="no data (cohort=0, ${report_display_date})"
	d7_status="TODO"
fi

if (( quality_feedback_base > 0 )); then
	useful_current="$(format_pct "$useful_rate_pct")% (${useful_count}/${quality_feedback_base}, ${report_display_date})"
	noise_current="$(format_pct "$noise_rate_pct")% (${noise_count}/${quality_feedback_base}, ${report_display_date})"
	if is_ge "$useful_rate_pct" "70"; then
		useful_status="DONE"
	else
		useful_status="TODO"
	fi
	if is_le "$noise_rate_pct" "20"; then
		noise_status="DONE"
	else
		noise_status="TODO"
	fi
else
	useful_current="no data (quality feedback=0, ${report_display_date})"
	noise_current="no data (quality feedback=0, ${report_display_date})"
	useful_status="TODO"
	noise_status="TODO"
fi

if (( delivery_users > 0 )); then
	premium_current="$(format_pct "$premium_intent_pct")% (${premium_intent_users}/${delivery_users}, ${report_display_date})"
	if is_ge "$premium_intent_pct" "10"; then
		premium_status="DONE"
	else
		premium_status="TODO"
	fi
else
	premium_current="no data (delivered=0, ${report_display_date})"
	premium_status="TODO"
fi

today="${TODAY_OVERRIDE:-$(date +%F)}"
tmp_file="$(mktemp)"
last_updated_replaced="0"
d7_replaced="0"
useful_replaced="0"
noise_replaced="0"
premium_replaced="0"

while IFS= read -r line; do
	case "$line" in
		"Last updated: "*)
			printf 'Last updated: %s\n' "$today" >>"$tmp_file"
			last_updated_replaced="1"
			;;
		"| D7 retention |"*)
			printf '| D7 retention | `>= 35%%` | %s | %s |\n' "$d7_current" "$d7_status" >>"$tmp_file"
			d7_replaced="1"
			;;
		"| Useful |"*)
			printf '| Useful | `>= 70%%` | %s | %s |\n' "$useful_current" "$useful_status" >>"$tmp_file"
			useful_replaced="1"
			;;
		"| Noise |"*)
			printf '| Noise | `<= 20%%` | %s | %s |\n' "$noise_current" "$noise_status" >>"$tmp_file"
			noise_replaced="1"
			;;
		"| Premium intent |"*)
			printf '| Premium intent | `>= 10%%` | %s | %s |\n' "$premium_current" "$premium_status" >>"$tmp_file"
			premium_replaced="1"
			;;
		*)
			printf '%s\n' "$line" >>"$tmp_file"
			;;
	esac
done <"$TARGET_FILE"

if [[ "$last_updated_replaced" != "1" || "$d7_replaced" != "1" || "$useful_replaced" != "1" || "$noise_replaced" != "1" || "$premium_replaced" != "1" ]]; then
	rm -f "$tmp_file"
	echo "Target file does not contain the expected MVP progress markers." >&2
	exit 1
fi

if [[ "$DRY_RUN" == "true" ]]; then
	diff -u "$TARGET_FILE" "$tmp_file" || true
	rm -f "$tmp_file"
	exit 0
fi

mv "$tmp_file" "$TARGET_FILE"
echo "Updated $TARGET_FILE for report date $REPORT_DATE"
