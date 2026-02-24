# Kotlin Script Host (Gradle Project)

This project provides two runtime entrypoints while keeping dynamic script loading from `scripts/`.

## Run CLI host
```bash
cd /tmp/kotlin-scripts
./gradlew runCli --args='scripts/hello.hub.kts'
./gradlew runCli --args='scripts/hello.hub.kts --arg=name=Codex --arg=upper=true'
```

Watch mode:
```bash
./gradlew runCli --args='scripts/hello.hub.kts --watch --debounce-ms=200'
```

## Run Web host (Ktor)
```bash
./gradlew runWeb --args='--port=8080 --scripts-dir=./scripts'
```

Auth:
- Use `Authorization: Bearer <token>` for all APIs except `/health`.
- Token source:
  - Preferred: set env `HOST_API_TOKEN`.
  - Otherwise host auto-generates a token and stores it at `scripts/.host-api-token`.

Routes:
- `GET /health`
- `GET /scripts`
- `GET /scripts/{script}` (raw script content)
- `POST /scripts/{script}`
- `PUT /scripts/{script}`
- `DELETE /scripts/{script}`
- `GET /meta/{script}`
- `GET /run/{script}?k=v`
- `POST /run/{script}?k=v`

Examples:
```bash
curl 'http://127.0.0.1:8080/health'
TOKEN="$(cat scripts/.host-api-token)"
curl -H "Authorization: Bearer $TOKEN" 'http://127.0.0.1:8080/scripts'
curl -H "Authorization: Bearer $TOKEN" 'http://127.0.0.1:8080/scripts/hello'
curl -H "Authorization: Bearer $TOKEN" -X POST 'http://127.0.0.1:8080/scripts/new-api' --data-binary $'// @desc: new api\nval args: Array<String> = emptyArray()\nprintln("ok")'
curl -H "Authorization: Bearer $TOKEN" -X PUT 'http://127.0.0.1:8080/scripts/new-api' --data-binary $'// @desc: new api v2\nval args: Array<String> = emptyArray()\nprintln("ok-v2")'
curl -H "Authorization: Bearer $TOKEN" -X DELETE 'http://127.0.0.1:8080/scripts/new-api'
curl -H "Authorization: Bearer $TOKEN" 'http://127.0.0.1:8080/meta/hello'
curl -H "Authorization: Bearer $TOKEN" 'http://127.0.0.1:8080/run/hello?name=Alice&upper=true'
curl -H "Authorization: Bearer $TOKEN" -X POST 'http://127.0.0.1:8080/run/hello?name=Alice' -d 'from-body'
```

## Script Metadata & Args (`*.hub.kts`)
Scripts declare metadata in comments and receive request arguments through explicit `args` declaration:

```kotlin
// @desc: Demo greeting API
// @param: name | default=world | desc=Name to greet
// @param: token | required=true | desc=Required token

val args: Array<String> = emptyArray()
val kv = args.mapNotNull {
  val i = it.indexOf('=')
  if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
}.toMap()

val name = kv["name"] ?: "world"
val token = kv["token"] ?: error("token required")
println("hello $name, token=$token")
```

## Dynamic scripts
You can add/remove `*.hub.kts` files in `scripts/` at any time. The web host resolves scripts by route name (`/run/{script}` -> `scripts/{script}.hub.kts`) on each request, so newly added scripts are available immediately.

## Notes
- This keeps runtime behavior dynamic; Gradle is used for dependency resolution and launching, not for precompiling scripts.
- IDE completion for regular Kotlin sources (`src/main/kotlin`) is fully modelled by Gradle.
- You do not need a package/build artifact step before each run. `runCli` and `runWeb` launch directly from source; scripts are compiled on-demand per execution/request.
- For script files with custom extension (`*.hub.kts`), IDEA code insight is usually weaker than standard `*.main.kts` or module Kotlin sources. This is an IDE limitation for custom script definitions.

## Command CLI
A standalone CLI script is available at `tools/api-cli.main.kts` (independent from host internals, only HTTP calls).

Examples:
```bash
kotlin tools/api-cli.main.kts --base-url=http://127.0.0.1:8080 --token-file=./scripts/.host-api-token list
kotlin tools/api-cli.main.kts --token-file=./scripts/.host-api-token show hello
kotlin tools/api-cli.main.kts --token-file=./scripts/.host-api-token run hello --arg=name=Alice --arg=upper=true
kotlin tools/api-cli.main.kts --token-file=./scripts/.host-api-token create demo --text='// @desc: demo\nval args: Array<String> = emptyArray()\nprintln("ok")'
```

Note:
- In this environment, `elide run <kts> -- <args...>` currently does not expose Kotlin script args reliably; use `kotlin` to run the CLI script.

## Simple TUI
A minimal keyboard-driven TUI is available at `tools/api-tui.main.kts`.

Run:
```bash
kotlin tools/api-tui.main.kts --base-url=http://127.0.0.1:8080 --token-file=./scripts/.host-api-token
```

Keys:
- `Up/Down` or `j/k`: switch script
- `Left/Right` or `h/l`: switch action (`Refresh/Show/Run/Meta/Create/Edit/Delete/Quit`)
- `Enter`: execute selected action
- `q`: quit

Create/Edit/Delete behavior:
- `Create`: prompt script name, then choose source mode:
  - `e` (default): create temp file, open terminal editor, then upload via API
  - `f`: read a specified local file and upload via API
  - In editor mode, if content is unchanged from initial template, creation is cancelled
- `Edit`: fetch current script content (`GET /scripts/{script}`), write to temp file, open editor, save+exit, then upload via `PUT`
- `Delete`: asks confirmation before calling `DELETE`
- `Run`: prompts for optional query args (`k=v`, separated by `&` or space), and optional POST mode/body
  - Now uses a keyboard-driven sub-menu (`Method/Query/Body/Execute/Cancel`) and remembers last run config per script during the session

Editor selection:
- First uses `$EDITOR`
- Fallback to first available of `nvim`, `vim`, `nano`

## Docker
Build image:
```bash
docker build -t slhaf-hub:latest .
```

Run container (mount local scripts directory):
```bash
docker run --rm -p 8080:8080 \
  -v /tmp/kotlin-scripts/scripts:/app/scripts \
  -e HOST_API_TOKEN=your-token \
  slhaf-hub:latest
```

Then call APIs:
```bash
curl http://127.0.0.1:8080/health
curl -H "Authorization: Bearer your-token" http://127.0.0.1:8080/scripts
```

## Docker Compose
Run with compose:
```bash
# optional: export HOST_API_TOKEN=your-token
# optional: export HOST_PORT=8080
docker compose up -d --build
```

Check status/logs:
```bash
docker compose ps
docker compose logs -f slhaf-hub
```

Stop:
```bash
docker compose down
```
