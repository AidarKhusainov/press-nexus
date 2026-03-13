#!/usr/bin/env bash
set -euo pipefail

domain="${1:-${PRESS_PUBLIC_DOMAIN:-}}"

if [[ -z "${domain}" ]]; then
	echo "Usage: PRESS_PUBLIC_DOMAIN=<domain> TELEGRAM_BOT_TOKEN=... TELEGRAM_WEBHOOK_SECRET_TOKEN=... $0 [domain]" >&2
	exit 1
fi

: "${TELEGRAM_BOT_TOKEN:?Set TELEGRAM_BOT_TOKEN}"
: "${TELEGRAM_WEBHOOK_SECRET_TOKEN:?Set TELEGRAM_WEBHOOK_SECRET_TOKEN}"

webhook_url="https://${domain}/api/telegram/webhook"

curl -fsS "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
	--data-urlencode "url=${webhook_url}" \
	--data-urlencode "secret_token=${TELEGRAM_WEBHOOK_SECRET_TOKEN}" \
	--data-urlencode 'allowed_updates=["message","callback_query"]'

echo

curl -fsS "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getWebhookInfo"
echo
