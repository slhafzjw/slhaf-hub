# slhaf-hub

基于 Kotlin 的动态脚本宿主，提供 HTTP API、root/sub token 鉴权、脚本 metadata、超时控制，以及配套 CLI/TUI 工具。

语言版本：
- English: `README.md`
- 中文: `README.zh-CN.md`

## 功能概览
- 从 `scripts/*.hub.kts` 动态加载脚本，无需重启 host
- Root/Sub token 鉴权模型
- 脚本注释 metadata（`@desc`、`@timeout`、`@param`）
- 脚本 CRUD + run + meta API
- subtoken 管理 API
- 运行并发限制（`--max-run-concurrency`）
- 脚本执行超时（默认 10 秒，可脚本级覆盖）

## 环境要求
- JDK 17+
- Gradle（或 Gradle Wrapper）

## 快速启动
克隆项目:
```bash
git clone https://github.com/slhafzjw/slhaf-hub.git
cd slhaf-hub
```

### 服务端
#### 1) 终端启动（Gradle）
```bash
./gradlew runWeb --args='--host=0.0.0.0 --port=8080 --scripts-dir=./scripts'
```

#### 2) Docker 启动
```bash
docker build -t slhaf-hub:latest .
docker run --rm -p 8080:8080 \
  -v "$(pwd)/scripts:/app/scripts" \
  -e HOST_API_TOKEN=your-token \
  -e MAX_RUN_CONCURRENCY=8 \
  slhaf-hub:latest
```

#### 3) Docker Compose 启动
```bash
# 可选：export HOST_API_TOKEN=your-token
# 可选：export HOST_PORT=8080
# 可选：export MAX_RUN_CONCURRENCY=8
docker compose up -d --build
```

健康检查：
```bash
curl http://127.0.0.1:8080/health
```

### 客户端
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

CLI/TUI 环境变量：
- `SLHAF_HUB_BASE_URL`
- `SLHAF_HUB_TOKEN`
- `SLHAF_HUB_TOKEN_FILE`

## 鉴权模型
鉴权请求头：
- `Authorization: Bearer <token>`（推荐）
- `X-Host-Token: <token>`

Token 来源优先级：
1. 环境变量 `HOST_API_TOKEN`
2. `scripts/.host-api-token`
3. 自动生成并写入 `scripts/.host-api-token`

Token 类型：
- `root`：完整权限
- `sub`：可访问 `/health`、`/type`、过滤后的 `/scripts`，以及被授权脚本的 `/meta/{script}` 与 `/run/{script}`

## 脚本 Metadata（`*.hub.kts`）
示例：
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

字段：
- `@desc: <text>`
- `@timeout: <value>`
- `@param: <name> | required=true|false | default=<value> | desc=<text>`

超时格式：
- `500ms`、`10s`、`1m`，或纯数字秒（`10`）

默认超时：
- `10s`

### Metadata 校验
`POST /scripts/{script}` 与 `PUT /scripts/{script}` 在保存前会校验 metadata。

校验失败时返回 `400 Bad Request`，并包含：
- 行级错误原因
- 正确格式示例

## API 概览
公开接口：
- `GET /health`

鉴权后接口：
- `GET /type`
- `GET /scripts`
- `GET /meta/{script}`
- `GET /run/{script}?k=v`
- `POST /run/{script}?k=v`

仅 root：
- `GET /scripts/{script}`
- `POST /scripts/{script}`
- `PUT /scripts/{script}`
- `DELETE /scripts/{script}`
- `GET /subtokens`
- `GET /subtokens/{name}`
- `POST /subtokens/{name}`
- `PUT /subtokens/{name}`
- `DELETE /subtokens/{name}`

常见状态码：
- `200`、`201`、`400`、`401`、`403`、`404`、`408`（超时）

## 运行控制
并发限制：
- 启动参数：`--max-run-concurrency=<N>`
- Compose 环境变量：`MAX_RUN_CONCURRENCY`
- 默认值：可用处理器数量

并发限制仅作用于 `/run/*` 接口。

## 自动化测试
执行：
```bash
./gradlew test
```

当前自动化覆盖 WebHost 接口：
- 鉴权行为
- 脚本 CRUD/meta/run
- metadata 校验返回
- subtoken 权限过滤
- run 超时行为

## 目录结构
- `src/main/kotlin/work/slhaf/hub`：host 实现
- `src/test/kotlin/work/slhaf/hub`：自动化测试
- `scripts`：运行时脚本和 token/subtoken 存储
- `tools`：独立 CLI/TUI 脚本
- `Dockerfile`、`docker-compose.yml`：容器部署
