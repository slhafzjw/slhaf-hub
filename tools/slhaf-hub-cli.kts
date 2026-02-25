#!/usr/bin/env kotlin

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

val ENV_API_BASE_URL = "SLHAF_HUB_BASE_URL"
val ENV_API_TOKEN = "SLHAF_HUB_TOKEN"

data class GlobalOptions(
    val baseUrl: String,
    val token: String?,
    val tokenFile: String?,
)

data class ParsedInput(
    val global: GlobalOptions,
    val command: String,
    val commandArgs: List<String>,
)

fun usage(): String =
    """
Usage:
  kotlin slhaf-hub-cli.kts [global options] <command> [command options]

Global options:
  --base-url=<url>               Default: SLHAF_HUB_BASE_URL or http://127.0.0.1:8080
  --token=<token>                Authorization token
  --token-file=<path>            Load token from file (fallback: SLHAF_HUB_TOKEN env)

Commands:
  health
  template <script>
  type
  list
  show <script>
  meta <script>
  run <script> [--arg=k=v ...] [--body=text] [--post]
  create <script> (--file=<path> | --text=<content>)
  update <script> (--file=<path> | --text=<content>)
  delete <script>

  sub-list
  sub-show <name>
  sub-create <name> --scripts=a,b,c
  sub-update <name> --scripts=a,b,c
  sub-delete <name>

Examples:
  kotlin slhaf-hub-cli.kts template hello
  kotlin slhaf-hub-cli.kts --token-file=./scripts/.host-api-token type
  kotlin slhaf-hub-cli.kts --token-file=./scripts/.host-api-token sub-list
  kotlin slhaf-hub-cli.kts --token-file=./scripts/.host-api-token sub-create demo --scripts=hello,time
    """.trimIndent()

fun parseInput(args: List<String>): ParsedInput {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        println(usage())
        exitProcess(0)
    }

    var baseUrl = System.getenv(ENV_API_BASE_URL)?.trim().orEmpty().ifBlank { "http://127.0.0.1:8080" }
    var token: String? = null
    var tokenFile: String? = null
    var i = 0
    while (i < args.size && args[i].startsWith("--")) {
        val arg = args[i]
        when {
            arg.startsWith("--base-url=") -> baseUrl = arg.substringAfter("=")
            arg.startsWith("--token=") -> token = arg.substringAfter("=")
            arg.startsWith("--token-file=") -> tokenFile = arg.substringAfter("=")
            else -> break
        }
        i++
    }

    if (i >= args.size) error("Missing command.\n${usage()}")
    val command = args[i]
    val commandArgs = args.drop(i + 1)
    return ParsedInput(GlobalOptions(baseUrl.trimEnd('/'), token, tokenFile), command, commandArgs)
}

fun readToken(options: GlobalOptions): String? {
    if (!options.token.isNullOrBlank()) return options.token
    if (!options.tokenFile.isNullOrBlank()) {
        val file = File(options.tokenFile)
        if (!file.exists()) error("Token file not found: ${file.absolutePath}")
        return file.readText().trim().ifBlank { null }
    }
    return System.getenv(ENV_API_TOKEN)?.trim()?.ifBlank { null }
}

fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

fun requireScriptName(args: List<String>): String {
    if (args.isEmpty()) error("Missing <script> argument.")
    return args.first()
}

fun requireNameArg(args: List<String>, label: String): String {
    if (args.isEmpty()) error("Missing <$label> argument.")
    return args.first()
}

fun parseBodyAndSource(args: List<String>): Pair<String?, String?> {
    val fileArg = args.firstOrNull { it.startsWith("--file=") }?.substringAfter("=")
    val textArg = args.firstOrNull { it.startsWith("--text=") }?.substringAfter("=")
    if (fileArg != null && textArg != null) error("Use either --file or --text, not both.")
    if (fileArg == null && textArg == null) error("Missing --file or --text.")
    return fileArg to textArg
}

fun parseRunArgs(args: List<String>): Triple<String, List<Pair<String, String>>, Pair<Boolean, String?>> {
    val script = requireScriptName(args)
    val rest = args.drop(1)
    val query = mutableListOf<Pair<String, String>>()
    var body: String? = null
    var post = false
    for (arg in rest) {
        when {
            arg == "--post" -> {
                post = true
            }

            arg.startsWith("--body=") -> {
                body = arg.substringAfter("=")
            }

            arg.startsWith("--arg=") -> {
                val token = arg.substringAfter("--arg=")
                val idx = token.indexOf('=')
                if (idx <= 0) error("Invalid --arg format: $arg, expected --arg=key=value")
                query += token.substring(0, idx) to token.substring(idx + 1)
            }

            else -> {
                error("Unknown run option: $arg")
            }
        }
    }
    return Triple(script, query, post to body)
}

fun parseScriptsArg(args: List<String>): Set<String> {
    val raw = args.firstOrNull { it.startsWith("--scripts=") }?.substringAfter("=")
        ?: error("Missing --scripts=a,b,c")
    val items =
        raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    if (items.any { !Regex("[A-Za-z0-9._-]+$").matches(it) }) {
        error("Invalid script names in --scripts, only [A-Za-z0-9._-] allowed")
    }
    return items
}

fun initialScriptTemplate(name: String): String =
    """
// @desc: $name
// @timeout: 10s
// @response: text
// @param: sample | required=false | default=value | desc=example parameter

lateinit var args: Array<String>
val kv = args.mapNotNull {
    val i = it.indexOf('=')
    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
}.toMap()

println("script=$name")
println("sample=" + (kv["sample"] ?: "value"))
    """.trimIndent()

fun request(
    client: HttpClient,
    baseUrl: String,
    token: String?,
    method: String,
    path: String,
    body: String? = null,
): Pair<Int, String> {
    val reqBuilder =
        HttpRequest
            .newBuilder(URI.create("$baseUrl$path"))
            .header("Accept", "text/plain,application/json")
    if (!token.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $token")

    val request =
        when (method) {
            "GET" -> reqBuilder.GET().build()
            "DELETE" -> reqBuilder.DELETE().build()
            "POST" -> reqBuilder.header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body ?: "")).build()
            "PUT" -> reqBuilder.header("Content-Type", "text/plain; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(body ?: "")).build()
            else -> error("Unsupported method: $method")
        }

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    return response.statusCode() to response.body()
}

fun main(args: Array<String>) {
    try {
        val input = parseInput(args.toList())
        val token = readToken(input.global)
        val client = HttpClient.newHttpClient()
        val base = input.global.baseUrl

        val (status, body) =
            when (input.command) {
                "health" -> request(client, base, null, "GET", "/health")
                "template" -> {
                    val script = requireScriptName(input.commandArgs)
                    200 to initialScriptTemplate(script)
                }
                "type" -> request(client, base, token, "GET", "/type")
                "list" -> request(client, base, token, "GET", "/scripts")
                "show" -> {
                    val script = requireScriptName(input.commandArgs)
                    request(client, base, token, "GET", "/scripts/${encode(script)}")
                }

                "meta" -> {
                    val script = requireScriptName(input.commandArgs)
                    request(client, base, token, "GET", "/meta/${encode(script)}")
                }

                "run" -> {
                    val (script, queryPairs, postAndBody) = parseRunArgs(input.commandArgs)
                    val query =
                        if (queryPairs.isEmpty()) "" else queryPairs.joinToString("&", prefix = "?") { (k, v) -> "${encode(k)}=${encode(v)}" }
                    val (post, postBody) = postAndBody
                    val method = if (post) "POST" else "GET"
                    request(client, base, token, method, "/run/${encode(script)}$query", postBody)
                }

                "create" -> {
                    val script = requireScriptName(input.commandArgs)
                    val (fileArg, textArg) = parseBodyAndSource(input.commandArgs.drop(1))
                    val bodyContent = if (fileArg != null) File(fileArg).readText() else textArg ?: ""
                    request(client, base, token, "POST", "/scripts/${encode(script)}", bodyContent)
                }

                "update" -> {
                    val script = requireScriptName(input.commandArgs)
                    val (fileArg, textArg) = parseBodyAndSource(input.commandArgs.drop(1))
                    val bodyContent = if (fileArg != null) File(fileArg).readText() else textArg ?: ""
                    request(client, base, token, "PUT", "/scripts/${encode(script)}", bodyContent)
                }

                "delete" -> {
                    val script = requireScriptName(input.commandArgs)
                    request(client, base, token, "DELETE", "/scripts/${encode(script)}")
                }

                "sub-list" -> request(client, base, token, "GET", "/subtokens")
                "sub-show" -> {
                    val name = requireNameArg(input.commandArgs, "name")
                    request(client, base, token, "GET", "/subtokens/${encode(name)}")
                }

                "sub-create" -> {
                    val name = requireNameArg(input.commandArgs, "name")
                    val scripts = parseScriptsArg(input.commandArgs.drop(1))
                    request(client, base, token, "POST", "/subtokens/${encode(name)}", scripts.joinToString("\n"))
                }

                "sub-update" -> {
                    val name = requireNameArg(input.commandArgs, "name")
                    val scripts = parseScriptsArg(input.commandArgs.drop(1))
                    request(client, base, token, "PUT", "/subtokens/${encode(name)}", scripts.joinToString("\n"))
                }

                "sub-delete" -> {
                    val name = requireNameArg(input.commandArgs, "name")
                    request(client, base, token, "DELETE", "/subtokens/${encode(name)}")
                }

                else -> error("Unknown command: ${input.command}\n${usage()}")
            }

        println(body)
        if (status >= 400) {
            System.err.println("[HTTP $status]")
            exitProcess(1)
        }
    } catch (e: Throwable) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}

main(args)
