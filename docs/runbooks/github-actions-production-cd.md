# GitHub Actions Production CD

## Goal

Publish a production-ready container image in CI and deploy that exact image to the production server over SSH.

## Workflow

- File: `.github/workflows/ci.yml`
- Builds and verifies the app on every PR and branch push
- Publishes `ghcr.io/<repo>:<full-commit-sha>` on successful pushes to `main` or `master`
- Publishes `ghcr.io/<repo>:prod` on the default branch for convenience
- File: `.github/workflows/cd.yml`
- Triggered automatically after a successful `CI` workflow on `main` or `master`
- Can also be started manually with `workflow_dispatch`
- Resolves the immutable digest for the published `:<full-commit-sha>` image tag and deploys by digest

## Required GitHub Secrets

Repository or `production` environment secrets:

- `PROD_SSH_USER` - SSH user on the production server
- `PROD_HOST` - production server host or IP
- `PROD_SSH_KEY` - private key for the production server
- `PROD_SSH_FINGERPRINT` - expected SHA256 fingerprint of the production SSH host key
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

- `deploy-bundle.tar` containing `compose.prod.yml` and generated `.env`

into `/opt/press-nexus` and then runs the deploy commands over SSH.

The GHCR credentials are not stored in `.env`; they are passed only to the deploy session.
The CD workflow does not rebuild the app image. It deploys the image already published by CI.
The SSH private key is normalized before use, so `PROD_SSH_KEY` may be stored as multiline PEM/OpenSSH text, escaped `\n`, or base64-encoded private key content.
SSH host verification is enforced by checking the live host key fingerprint against `PROD_SSH_FINGERPRINT`.

## What The Deploy Does

1. Resolves the immutable digest for the image published by CI.
2. Uploads a deploy bundle containing `.env` and `compose.prod.yml`.
3. Logs into `ghcr.io` on the server.
4. Pulls the new application image by digest.
5. Starts the app with the `prod` profile through Docker Compose.
6. Lets Compose bootstrap PostgreSQL, Ollama, and the one-shot `ollama-model-init` service.
7. Runs smoke checks against `localhost:$APP_PORT`.

## Rollback

1. SSH to the server.
2. Run `docker login ghcr.io`.
3. Open `/opt/press-nexus/.env`.
4. Replace `APP_IMAGE` with the previous immutable GHCR image reference.
5. Run `docker compose --env-file /opt/press-nexus/.env -f /opt/press-nexus/compose.prod.yml up -d app`.

## Notes

- The workflow deploys the app stack itself. HTTPS ingress and Telegram webhook registration are still managed separately.
- The generated `.env` contains application secrets, so the target directory should stay readable only by the deploy user or root.
- Put production secrets in the `production` environment when possible and protect that environment with required reviewers.
- If the server SSH host key rotates, update `PROD_SSH_FINGERPRINT` before rerunning CD.
