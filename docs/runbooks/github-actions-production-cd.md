# GitHub Actions Production CD

## Goal

Build the production image, push it to GHCR, and deploy it to the production server over SSH.

## Workflow

- File: `.github/workflows/cd.yml`
- Triggered automatically after a successful `CI` workflow on `main` or `master`
- Can also be started manually with `workflow_dispatch`

## Required GitHub Secrets

Repository or `production` environment secrets:

- `PROD_SSH_USER` - SSH user on the production server
- `PROD_HOST` - production server host or IP
- `PROD_SSH_KEY` - private key for the production server
- `PROD_SSH_FINGERPRINT` - SHA256 fingerprint of the production SSH host key
- `PROD_GHCR_USERNAME` - GHCR username allowed to pull private images
- `PROD_GHCR_TOKEN` - GHCR token with `read:packages`
- `PRESS_DB_USER` - optional, defaults to `pressnexus`
- `PRESS_DB_PASSWORD`
- `PRESS_API_KEY`
- `TELEGRAM_WEBHOOK_SECRET_TOKEN`
- `TELEGRAM_BOT_TOKEN`
- `GEMINI_API_KEY`
- `PRESS_DELIVERY_TELEGRAM_ENABLED` - recommended `false` for the first rollout
- `APP_BIND_ADDRESS` - recommended `127.0.0.1` if a reverse proxy is installed, otherwise `0.0.0.0`
- `APP_PORT` - optional, defaults to `8080`
- `OLLAMA_THREADS` - optional, defaults to `4`
- `JAVA_TOOL_OPTIONS` - optional JVM tuning, defaults to `-XX:MaxRAMPercentage=75.0`

## Server Prerequisites

The workflow assumes the server already has:

- Docker Engine
- Docker Compose plugin or `docker-compose`
- `curl`
- enough disk space for PostgreSQL data, Ollama models, and Docker images

The workflow uploads:

- `deploy-bundle.tar` containing `docker-compose.prod.yml`, `deploy-prod-stack.sh`, and generated `.env`

into `/opt/press-nexus` and then runs the remote deploy script there.

The GHCR credentials are not stored in `.env`; they are passed only to the deploy session.
SSH steps run through `appleboy/scp-action@v1` and `appleboy/ssh-action@v1`, with server verification via `PROD_SSH_FINGERPRINT`.

## What The Deploy Does

1. Logs into `ghcr.io` on the server.
2. Starts PostgreSQL and Ollama.
3. Pulls `nomic-embed-text` into Ollama.
4. Pulls the new application image.
5. Starts the app with the `prod` profile.
6. Runs smoke checks against `localhost:$APP_PORT`.

## Rollback

1. SSH to the server.
2. Run `docker login ghcr.io`.
3. Open `/opt/press-nexus/.env`.
4. Replace `APP_IMAGE` with the previous immutable GHCR image tag.
5. Run `APP_DIR=/opt/press-nexus /opt/press-nexus/deploy-prod-stack.sh`.

## Notes

- The workflow deploys the app stack itself. HTTPS ingress and Telegram webhook registration are still managed separately.
- The generated `.env` contains application secrets, so the target directory should stay readable only by the deploy user or root.
- To get the fingerprint secret value, run `ssh your-host ssh-keygen -l -f /etc/ssh/ssh_host_ed25519_key.pub | cut -d ' ' -f2` on the server and store the resulting `SHA256:...` string in `PROD_SSH_FINGERPRINT`.
