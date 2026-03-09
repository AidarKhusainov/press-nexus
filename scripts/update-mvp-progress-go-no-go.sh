#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_FILE="$REPO_ROOT/docs/MVP_PROGRESS.md"

API_BASE_URL="http://localhost:8080"
API_KEY="${PRESS_API_KEY:-}"
PROMETHEUS_BASE_URL=""
PROMETHEUS_APPLICATION="app"
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
  --api-key KEY              Internal API key for protected report endpoint
  --prometheus-base-url URL  Read metrics from Prometheus instant query API instead of HTTP report API
  --prometheus-application   `application` label for Prometheus metrics (default: app)
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
		--api-key)
			API_KEY="${2:-}"
			shift 2
			;;
		--prometheus-base-url)
			PROMETHEUS_BASE_URL="${2:-}"
			shift 2
			;;
		--prometheus-application)
			PROMETHEUS_APPLICATION="${2:-}"
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

curl_json() {
	curl --fail --show-error --silent \
		--connect-timeout "$CURL_MAX_TIME_SECONDS" \
		--max-time "$CURL_MAX_TIME_SECONDS" \
		--retry "$CURL_RETRY_COUNT" \
		--retry-delay "$CURL_RETRY_DELAY_SECONDS" \
		--retry-all-errors \
		"$@"
}

curl_internal_api_json() {
	local extra=()
	if [[ -n "$API_KEY" ]]; then
		extra+=(-H "X-PressNexus-Api-Key: $API_KEY")
	fi
	curl_json "${extra[@]}" "$@"
}

fetch_prometheus_scalar() {
	local query="$1"
	local response
	response="$(curl_json --get --data-urlencode "query=$query" "$PROMETHEUS_BASE_URL/api/v1/query")"
	jq -r 'if .status == "success" then (.data.result[0].value[1] // empty) else empty end' <<<"$response"
}

prometheus_number_or_zero() {
	local query="$1"
	local value
	value="$(fetch_prometheus_scalar "$query")"
	if [[ -z "$value" || "$value" == "null" ]]; then
		echo "0"
	else
		echo "$value"
	fi
}

if [[ -n "$REPORT_JSON_FILE" ]]; then
	if [[ ! -f "$REPORT_JSON_FILE" ]]; then
		echo "Report JSON file not found: $REPORT_JSON_FILE" >&2
		exit 1
	fi
	REPORT_JSON="$(cat "$REPORT_JSON_FILE")"
elif [[ -n "$PROMETHEUS_BASE_URL" ]]; then
	short_window="2d"
	long_window="8d"
	label_selector="{application=\"$PROMETHEUS_APPLICATION\"}"

	delivery_users="$(prometheus_number_or_zero "last_over_time(press_product_report_delivery_users${label_selector}[${short_window}])")"
	feedback_users="$(prometheus_number_or_zero "last_over_time(press_product_report_feedback_users${label_selector}[${short_window}])")"
	useful_count="$(prometheus_number_or_zero "last_over_time(press_product_report_useful_count${label_selector}[${short_window}])")"
	noise_count="$(prometheus_number_or_zero "last_over_time(press_product_report_noise_count${label_selector}[${short_window}])")"
	anxious_count="$(prometheus_number_or_zero "last_over_time(press_product_report_anxious_count${label_selector}[${short_window}])")"
	useful_rate_pct="$(prometheus_number_or_zero "last_over_time(press_product_report_useful_rate_pct${label_selector}[${short_window}])")"
	noise_rate_pct="$(prometheus_number_or_zero "last_over_time(press_product_report_noise_rate_pct${label_selector}[${short_window}])")"
	premium_intent_users="$(prometheus_number_or_zero "last_over_time(press_product_report_premium_intent_users${label_selector}[${short_window}])")"
	premium_intent_pct="$(prometheus_number_or_zero "last_over_time(press_product_report_premium_intent_pct${label_selector}[${short_window}])")"
	d1_cohort_size="$(prometheus_number_or_zero "last_over_time(press_product_report_d1_cohort_size${label_selector}[${short_window}])")"
	d1_retained_users="$(prometheus_number_or_zero "last_over_time(press_product_report_d1_retained_users${label_selector}[${short_window}])")"
	d1_retention_pct="$(prometheus_number_or_zero "last_over_time(press_product_report_d1_retention_pct${label_selector}[${short_window}])")"
	d7_cohort_size="$(prometheus_number_or_zero "last_over_time(press_product_report_d7_cohort_size${label_selector}[${long_window}])")"
	d7_retained_users="$(prometheus_number_or_zero "last_over_time(press_product_report_d7_retained_users${label_selector}[${long_window}])")"
	d7_retention_pct="$(prometheus_number_or_zero "last_over_time(press_product_report_d7_retention_pct${label_selector}[${long_window}])")"

	REPORT_JSON="$(jq -n \
		--arg reportDate "$REPORT_DATE" \
		--argjson deliveryUsers "$delivery_users" \
		--argjson feedbackUsers "$feedback_users" \
		--argjson usefulCount "$useful_count" \
		--argjson noiseCount "$noise_count" \
		--argjson anxiousCount "$anxious_count" \
		--argjson usefulRatePct "$useful_rate_pct" \
		--argjson noiseRatePct "$noise_rate_pct" \
		--argjson premiumIntentUsers "$premium_intent_users" \
		--argjson premiumIntentPct "$premium_intent_pct" \
		--argjson d1CohortSize "$d1_cohort_size" \
		--argjson d1RetainedUsers "$d1_retained_users" \
		--argjson d1RetentionPct "$d1_retention_pct" \
		--argjson d7CohortSize "$d7_cohort_size" \
		--argjson d7RetainedUsers "$d7_retained_users" \
		--argjson d7RetentionPct "$d7_retention_pct" \
		'{
			reportDate: $reportDate,
			deliveryUsers: $deliveryUsers,
			feedbackUsers: $feedbackUsers,
			usefulCount: $usefulCount,
			noiseCount: $noiseCount,
			anxiousCount: $anxiousCount,
			usefulRatePct: $usefulRatePct,
			noiseRatePct: $noiseRatePct,
			premiumIntentUsers: $premiumIntentUsers,
			premiumIntentPct: $premiumIntentPct,
			d1CohortSize: $d1CohortSize,
			d1RetainedUsers: $d1RetainedUsers,
			d1RetentionPct: $d1RetentionPct,
			d7CohortSize: $d7CohortSize,
			d7RetainedUsers: $d7RetainedUsers,
			d7RetentionPct: $d7RetentionPct
		}')"
else
	REPORT_JSON="$(curl_internal_api_json "$API_BASE_URL/api/analytics/product-report/daily?date=$REPORT_DATE")"
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

today="${TODAY_OVERRIDE:-$(date +%F)}"
tmp_file="$(mktemp)"
last_updated_replaced="0"
d7_replaced="0"
useful_replaced="0"
noise_replaced="0"

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
		*)
			printf '%s\n' "$line" >>"$tmp_file"
			;;
	esac
done <"$TARGET_FILE"

if [[ "$last_updated_replaced" != "1" || "$d7_replaced" != "1" || "$useful_replaced" != "1" || "$noise_replaced" != "1" ]]; then
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
