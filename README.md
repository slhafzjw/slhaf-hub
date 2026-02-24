# slhaf-hub

Kotlin-based dynamic script host with HTTP APIs, root/sub token auth, script metadata, timeout control, and companion CLI/TUI tools.

Language:
- English: `README.md`
- 中文: `README.zh-CN.md`

## Features
- Dynamic script loading from `scripts/*.hub.kts` without restarting host
- Root/Sub token authorization model
- Metadata in script comments (`@desc`, `@timeout`, `@param`)
- Script CRUD + run + metadata APIs
- Subtoken management APIs
- Run concurrency limit (`--max-run-concurrency`)
- Script timeout (default 10s, script-level override)

## Requirements
- JDK 17+
- Gradle (or Gradle wrapper)

## Quick Start
### Server
#### 1) Run from terminal (Gradle)
```bash
cd /tmp/kotlin-scripts
./gradlew runWeb --args='--host=0.0.0.0 --port=8080 --scripts-dir=./scripts'
```

#### 2) Run with Docker
```bash
docker build -t slhaf-hub:latest .
docker run --rm -p 8080:8080 \
  -v /tmp/kotlin-scripts/scripts:/app/scripts \
  -e HOST_API_TOKEN=your-token \
  -e MAX_RUN_CONCURRENCY=8 \
  slhaf-hub:latest
```

#### 3) Run with Docker Compose
```bash
# optional: export HOST_API_TOKEN=your-token
# optional: export HOST_PORT=8080
# optional: export MAX_RUN_CONCURRENCY=8
docker compose up -d --build
```

Health check:
```bash
curl http://127.0.0.1:8080/health
```

### Clients
#### CLI
```bash
kotlin tools/slhaf-hub-cli.kts --base-url=http://127.0.0.1:8080 --token-file=./scripts/.host-api-token list
kotlin tools/slhaf-hub-cli.kts --token-file=./scripts/.host-api-token type
kotlin tools/slhaf-hub-cli.kts --token-file=./scripts/.host-api-token run hello --arg=name=Alice --arg=upper=true
```

#### TUI
```bash
kotlin tools/slhaf-hub-tui.kts --base-url=http://127.0.0.1:8080 --token-file=./scripts/.host-api-token
```

CLI/TUI env vars:
- `SLHAF_HUB_BASE_URL`
- `SLHAF_HUB_TOKEN`
- `SLHAF_HUB_TOKEN_FILE`

## Auth Model
Auth headers:
- `Authorization: Bearer <token>` (recommended)
- `X-Host-Token: <token>`

Token source priority:
1. `HOST_API_TOKEN` env var
2. `scripts/.host-api-token`
3. Auto-generated token saved to `scripts/.host-api-token`

Token types:
- `root`: full access
- `sub`: access to `/health`, `/type`, filtered `/scripts`, and allowed-script `/meta/{script}` + `/run/{script}`

## Script Metadata (`*.hub.kts`)
Example:
```kotlin
// @desc: Demo greeting API
// @timeout: 10s
// @param: name | required=false | default=world | desc=Name to greet

val args: Array<String> = emptyArray()
val kv = args.mapNotNull {
    val i = it.indexOf('=')
    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
}.toMap()

println("hello " + (kv["name"] ?: "world"))
```

Fields:
- `@desc: <text>`
- `@timeout: <value>`
- `@param: <name> | required=true|false | default=<value> | desc=<text>`

Timeout formats:
- `500ms`, `10s`, `1m`, or plain integer seconds (`10`)

Default timeout:
- `10s`

### Metadata Validation
`POST /scripts/{script}` and `PUT /scripts/{script}` validate metadata before saving.

On validation failure, server returns `400 Bad Request` with:
- line-based reason details
- valid metadata examples

## API Summary
Public:
- `GET /health`

Authenticated:
- `GET /type`
- `GET /scripts`
- `GET /meta/{script}`
- `GET /run/{script}?k=v`
- `POST /run/{script}?k=v`

Root only:
- `GET /scripts/{script}`
- `POST /scripts/{script}`
- `PUT /scripts/{script}`
- `DELETE /scripts/{script}`
- `GET /subtokens`
- `GET /subtokens/{name}`
- `POST /subtokens/{name}`
- `PUT /subtokens/{name}`
- `DELETE /subtokens/{name}`

Common statuses:
- `200`, `201`, `400`, `401`, `403`, `404`, `408` (timeout)

## Runtime Controls
Run concurrency:
- arg: `--max-run-concurrency=<N>`
- env (compose): `MAX_RUN_CONCURRENCY`
- default: number of available processors

The limit only applies to `/run/*` endpoints.

## Testing
Run:
```bash
./gradlew test
```

Current automated coverage focuses on WebHost APIs:
- auth behavior
- script CRUD/meta/run
- metadata validation responses
- subtoken permission filtering
- run timeout behavior

## Project Layout
- `src/main/kotlin/work/slhaf/hub`: host implementation
- `src/test/kotlin/work/slhaf/hub`: automated tests
- `scripts`: runtime scripts and token/subtoken storage
- `tools`: standalone CLI/TUI scripts
- `Dockerfile`, `docker-compose.yml`: container deployment
