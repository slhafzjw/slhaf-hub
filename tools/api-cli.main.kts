#!/usr/bin/env kotlin

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

data class GlobalOptions(
    val baseUrl: String,
    val token: String?,
    val tokenFile: String?
)

data class ParsedInput(
    val global: GlobalOptions,
    val command: String,
    val commandArgs: List<String>
)

fun usage(): String = """
Usage:
  elide run api-cli.main.kts [global options] <command> [command options]

Global options:
  --base-url=<url>               Default: http://127.0.0.1:8080
  --token=<token>                Authorization token
  --token-file=<path>            Load token from file (fallback: HOST_API_TOKEN env)

Commands:
  health
  list
  show <script>
  meta <script>
  run <script> [--arg=k=v ...] [--body=text] [--post]
  create <script> (--file=<path> | --text=<content>)
  update <script> (--file=<path> | --text=<content>)
  delete <script>

Examples:
  elide run api-cli.main.kts --base-url=http://127.0.0.1:8080 --token-file=./scripts/.host-api-token list
  elide run api-cli.main.kts --token-file=./scripts/.host-api-token show hello
  elide run api-cli.main.kts --token-file=./scripts/.host-api-token run hello --arg=name=Alice --arg=upper=true
  elide run api-cli.main.kts --token-file=./scripts/.host-api-token create demo --file=./demo.hub.kts
""".trimIndent()

fun parseInput(args: List<String>): ParsedInput {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        println(usage())
        exitProcess(0)
    }

    var baseUrl = "http://127.0.0.1:8080"
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
    return System.getenv("HOST_API_TOKEN")?.trim()?.ifBlank { null }
}

fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

fun requireScriptName(args: List<String>): String {
    if (args.isEmpty()) error("Missing <script> argument.")
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
            arg == "--post" -> post = true
            arg.startsWith("--body=") -> body = arg.substringAfter("=")
            arg.startsWith("--arg=") -> {
                val token = arg.substringAfter("--arg=")
                val idx = token.indexOf('=')
                if (idx <= 0) error("Invalid --arg format: $arg, expected --arg=key=value")
                query += token.substring(0, idx) to token.substring(idx + 1)
            }
            else -> error("Unknown run option: $arg")
        }
    }
    return Triple(script, query, post to body)
}

fun request(
    client: HttpClient,
    baseUrl: String,
    token: String?,
    method: String,
    path: String,
    body: String? = null
): Pair<Int, String> {
    val reqBuilder = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
        .header("Accept", "text/plain,application/json")
    if (!token.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $token")

    val request = when (method) {
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

        val (status, body) = when (input.command) {
            "health" -> request(client, base, null, "GET", "/health")
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
                val query = if (queryPairs.isEmpty()) "" else queryPairs.joinToString("&", prefix = "?") { (k, v) ->
                    "${encode(k)}=${encode(v)}"
                }
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
