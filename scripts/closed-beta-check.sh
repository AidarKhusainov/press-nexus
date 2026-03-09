#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL="http://localhost:8080"
REPORT_DATE="$(date -d "yesterday" +%F)"
PREVIEW_HOURS="24"
PREVIEW_LIMIT="5"
LANGUAGE="ru"
TRIGGER_SEND_NOW="false"
PREVIEW_OUT=""
REPORT_JSON_OUT=""
CURL_MAX_TIME_SECONDS="${CURL_MAX_TIME_SECONDS:-10}"
CURL_RETRY_COUNT="${CURL_RETRY_COUNT:-3}"
CURL_RETRY_DELAY_SECONDS="${CURL_RETRY_DELAY_SECONDS:-1}"

usage() {
	cat <<'EOF'
Usage: ./scripts/closed-beta-check.sh [options]

Options:
  --api-base-url URL       API base URL (default: http://localhost:8080)
  --report-date YYYY-MM-DD Report date for /api/analytics/product-report/daily (default: yesterday)
  --preview-hours N        Hours for /api/brief/daily/text preview (default: 24)
  --preview-limit N        Limit for /api/brief/daily/text preview (default: 5)
  --lang LANG              Preview language (default: ru)
  --trigger-send-now       Trigger POST /api/brief/daily/send before reading report
  --preview-out PATH       Write preview text to file
  --report-json-out PATH   Write product-report JSON to file
  -h, --help               Show help
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--api-base-url)
			API_BASE_URL="${2:-}"
			shift 2
			;;
		--report-date)
			REPORT_DATE="${2:-}"
			shift 2
			;;
		--preview-hours)
			PREVIEW_HOURS="${2:-}"
			shift 2
			;;
		--preview-limit)
			PREVIEW_LIMIT="${2:-}"
			shift 2
			;;
		--lang)
			LANGUAGE="${2:-}"
			shift 2
			;;
		--trigger-send-now)
			TRIGGER_SEND_NOW="true"
			shift
			;;
		--preview-out)
			PREVIEW_OUT="${2:-}"
			shift 2
			;;
		--report-json-out)
			REPORT_JSON_OUT="${2:-}"
			shift 2
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

curl_text() {
	curl --fail --show-error --silent \
		--connect-timeout "$CURL_MAX_TIME_SECONDS" \
		--max-time "$CURL_MAX_TIME_SECONDS" \
		--retry "$CURL_RETRY_COUNT" \
		--retry-delay "$CURL_RETRY_DELAY_SECONDS" \
		--retry-all-errors \
		"$@"
}

curl_json() {
	curl_text "$@"
}

PREVIEW_TEXT="$(curl_text \
	"$API_BASE_URL/api/brief/daily/text?hours=$PREVIEW_HOURS&limit=$PREVIEW_LIMIT&lang=$LANGUAGE")"

if [[ -n "$PREVIEW_OUT" ]]; then
	printf "%s\n" "$PREVIEW_TEXT" >"$PREVIEW_OUT"
fi

SEND_RESULT="skipped"
if [[ "$TRIGGER_SEND_NOW" == "true" ]]; then
	SEND_RESPONSE="$(curl_json -X POST "$API_BASE_URL/api/brief/daily/send")"
	SEND_RESULT="$(jq -r '.sentChats // 0' <<<"$SEND_RESPONSE")"
fi

REPORT_JSON="$(curl_json "$API_BASE_URL/api/analytics/product-report/daily?date=$REPORT_DATE")"
if ! jq -e . >/dev/null 2>&1 <<<"$REPORT_JSON"; then
	echo "Invalid JSON payload from /api/analytics/product-report/daily" >&2
	exit 1
fi

if [[ -n "$REPORT_JSON_OUT" ]]; then
	printf "%s\n" "$REPORT_JSON" >"$REPORT_JSON_OUT"
fi

echo "Closed beta preview"
echo "==================="
echo "$PREVIEW_TEXT"
echo
echo "Delivery trigger sentChats: $SEND_RESULT"
echo
echo "Product report summary"
echo "======================"
echo "reportDate: $(jq -r '.reportDate // empty' <<<"$REPORT_JSON")"
echo "deliveryUsers: $(jq -r '.deliveryUsers // 0' <<<"$REPORT_JSON")"
echo "feedbackUsers: $(jq -r '.feedbackUsers // 0' <<<"$REPORT_JSON")"
echo "usefulRatePct: $(jq -r '.usefulRatePct // 0' <<<"$REPORT_JSON")"
echo "noiseRatePct: $(jq -r '.noiseRatePct // 0' <<<"$REPORT_JSON")"
echo "d1RetentionPct: $(jq -r '.d1RetentionPct // 0' <<<"$REPORT_JSON")"
echo "d7RetentionPct: $(jq -r '.d7RetentionPct // 0' <<<"$REPORT_JSON")"
