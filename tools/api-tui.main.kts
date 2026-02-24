#!/usr/bin/env kotlin

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.system.exitProcess

data class Options(
    val baseUrl: String,
    val token: String?,
    val tokenFile: String?
)

private val RESET = "\u001b[0m"
private val BOLD = "\u001b[1m"
private val DIM = "\u001b[2m"
private val CYAN = "\u001b[36m"
private val GREEN = "\u001b[32m"
private val YELLOW = "\u001b[33m"
private val RED = "\u001b[31m"
private val BG_BLUE = "\u001b[44m"
private val FG_BLACK = "\u001b[30m"

fun ok(text: String) = "$GREEN$text$RESET"
fun warn(text: String) = "$YELLOW$text$RESET"
fun err(text: String) = "$RED$text$RESET"
fun accent(text: String) = "$CYAN$text$RESET"
fun selected(text: String) = "$BG_BLUE$FG_BLACK$BOLD$text$RESET"

fun usage(): String = """
Usage:
  kotlin tools/api-tui.main.kts [--base-url=http://127.0.0.1:8080] [--token=<token> | --token-file=./scripts/.host-api-token]

Keys:
  Up/Down or j/k   Select script
  Left/Right or h/l Select action
  Enter            Execute action
  q                Quit
""".trimIndent()

fun parseOptions(args: List<String>): Options {
    if (args.contains("--help") || args.contains("-h")) {
        println(usage())
        exitProcess(0)
    }
    var baseUrl = "http://127.0.0.1:8080"
    var token: String? = null
    var tokenFile: String? = null
    args.forEach { arg ->
        when {
            arg.startsWith("--base-url=") -> baseUrl = arg.substringAfter("=")
            arg.startsWith("--token=") -> token = arg.substringAfter("=")
            arg.startsWith("--token-file=") -> tokenFile = arg.substringAfter("=")
            else -> error("Unknown option: $arg\n${usage()}")
        }
    }
    return Options(baseUrl.trimEnd('/'), token, tokenFile)
}

fun readToken(options: Options): String {
    if (!options.token.isNullOrBlank()) return options.token
    if (!options.tokenFile.isNullOrBlank()) {
        val file = File(options.tokenFile)
        if (!file.exists()) error("Token file not found: ${file.absolutePath}")
        return file.readText().trim()
    }
    return System.getenv("HOST_API_TOKEN")?.trim()
        ?: error("Missing token. Use --token or --token-file or HOST_API_TOKEN")
}

fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

fun request(
    client: HttpClient,
    baseUrl: String,
    token: String,
    method: String,
    path: String,
    body: String? = null
): Pair<Int, String> {
    val reqBuilder = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
        .header("Accept", "text/plain,application/json")
        .header("Authorization", "Bearer $token")
    val request = when (method) {
        "GET" -> reqBuilder.GET().build()
        "POST" -> reqBuilder.header("Content-Type", "text/plain; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body ?: "")).build()
        "PUT" -> reqBuilder.header("Content-Type", "text/plain; charset=utf-8")
            .PUT(HttpRequest.BodyPublishers.ofString(body ?: "")).build()
        "DELETE" -> reqBuilder.DELETE().build()
        else -> error("Unsupported method: $method")
    }
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    return response.statusCode() to response.body()
}

fun shell(cmd: String): String {
    val p = ProcessBuilder("bash", "-lc", cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return out.trim()
}

fun commandExists(cmd: String): Boolean =
    ProcessBuilder("bash", "-lc", "command -v $cmd >/dev/null 2>&1").start().waitFor() == 0

enum class Key {
    UP, DOWN, LEFT, RIGHT, ENTER, Q, OTHER
}

data class RunProfile(
    val method: String = "GET",
    val queryRaw: String = "",
    val body: String = ""
)

fun readKey(): Key {
    val first = System.`in`.read()
    if (first == -1) return Key.OTHER
    return when (first) {
        10, 13 -> Key.ENTER
        'q'.code, 'Q'.code -> Key.Q
        'k'.code, 'K'.code -> Key.UP
        'j'.code, 'J'.code -> Key.DOWN
        'h'.code, 'H'.code -> Key.LEFT
        'l'.code, 'L'.code -> Key.RIGHT
        27 -> {
            val second = System.`in`.read()
            val third = System.`in`.read()
            if (second == '['.code) {
                when (third) {
                    'A'.code -> Key.UP
                    'B'.code -> Key.DOWN
                    'C'.code -> Key.RIGHT
                    'D'.code -> Key.LEFT
                    else -> Key.OTHER
                }
            } else Key.OTHER
        }
        else -> Key.OTHER
    }
}

fun clearScreen() {
    print("\u001b[2J\u001b[H")
}

fun drawRunConfig(scriptName: String, profile: RunProfile, selected: Int, hint: String) {
    clearScreen()
    println("${accent("Run Config")}  ${DIM}script=$scriptName$RESET")
    println("${DIM}Up/Down select | Left/Right toggle | Enter edit/execute | q cancel$RESET")
    println()

    val rows = listOf(
        "Method: ${profile.method}",
        "Query: ${profile.queryRaw.ifBlank { "(empty)" }}",
        "Body: ${if (profile.method == "POST") profile.body.ifBlank { "(empty)" } else "(ignored for GET)"}",
        "Execute",
        "Cancel"
    )
    rows.forEachIndexed { idx, row ->
        if (idx == selected) println(" ${selected("> $row")}") else println("   $row")
    }
    println()
    println("${BOLD}Hint:$RESET ${colorizeStatusLine(hint)}")
}

fun colorizeStatusLine(line: String): String {
    return when {
        line.startsWith("[ERROR]") || line.startsWith("[HTTP 4") || line.startsWith("[HTTP 5") -> err(line)
        line.startsWith("Loaded") || line.contains("HTTP 200") || line.startsWith("[RUN") || line.startsWith("[SHOW") || line.startsWith("[META") || line.startsWith("[CREATE") || line.startsWith("[EDIT") || line.startsWith("[DELETE") -> ok(line)
        line.startsWith("No scripts.") || line.startsWith("[HTTP 3") || line.startsWith("[CANCEL]") -> warn(line)
        else -> line
    }
}

fun draw(
    baseUrl: String,
    scripts: List<String>,
    selectedScript: Int,
    actions: List<String>,
    selectedAction: Int,
    output: String
) {
    clearScreen()
    println("${accent("API TUI")}  ${DIM}base=$baseUrl$RESET")
    println("${DIM}Keys: Up/Down/j/k script | Left/Right/h/l action | Enter execute | q quit$RESET")
    println()

    print("${BOLD}Actions:$RESET ")
    actions.forEachIndexed { idx, name ->
        if (idx == selectedAction) print(selected(" $name ")) else print("[${accent(name)}] ")
    }
    println()
    println()
    println("${BOLD}Scripts:$RESET")
    if (scripts.isEmpty()) {
        println("  ${DIM}(no scripts)$RESET")
    } else {
        scripts.forEachIndexed { idx, name ->
            if (idx == selectedScript) {
                println(" ${selected("> $name")}")
            } else {
                println("   $name")
            }
        }
    }
    println()
    println("${BOLD}Output:$RESET")
    val lines = output.lines()
    lines.takeLast(max(1, 16)).forEach { println(colorizeStatusLine(it)) }
}

fun fetchScripts(client: HttpClient, baseUrl: String, token: String): Pair<List<String>, String> {
    return try {
        val (status, body) = request(client, baseUrl, token, "GET", "/scripts")
        if (status >= 400) return emptyList<String>() to "[HTTP $status]\n$body"
        val scripts = body.lines().map { it.trim() }.filter { it.isNotBlank() }.map { it.substringBefore('\t') }
        scripts to "Loaded ${scripts.size} script(s)."
    } catch (t: Throwable) {
        emptyList<String>() to "[ERROR] ${t::class.simpleName}: ${t.message}"
    }
}

fun chooseEditor(): String? {
    val env = System.getenv("EDITOR")?.trim()
    if (!env.isNullOrBlank()) return env
    val fallback = listOf("nvim", "vim", "nano").firstOrNull { commandExists(it) }
    return fallback
}

fun promptLine(oldStty: String, prompt: String): String {
    shell("stty $oldStty < /dev/tty")
    print("\u001b[?25h")
    print(prompt)
    val value = readLine().orEmpty()
    shell("stty -echo -icanon min 1 time 0 < /dev/tty")
    print("\u001b[?25l")
    return value.trim()
}

fun promptRawLine(oldStty: String, prompt: String): String {
    shell("stty $oldStty < /dev/tty")
    print("\u001b[?25h")
    print(prompt)
    val value = readLine().orEmpty()
    shell("stty -echo -icanon min 1 time 0 < /dev/tty")
    print("\u001b[?25l")
    return value
}

fun openEditor(oldStty: String, file: File): Pair<Boolean, String> {
    val editor = chooseEditor() ?: return false to "[ERROR] No editor found (set EDITOR or install nvim/vim/nano)."
    shell("stty $oldStty < /dev/tty")
    print("\u001b[?25h")
    println("Opening editor: $editor ${file.absolutePath}")
    val cmd = """$editor "${file.absolutePath.replace("\"", "\\\"")}""""
    val process = ProcessBuilder("bash", "-lc", cmd).inheritIO().start()
    val code = process.waitFor()
    shell("stty -echo -icanon min 1 time 0 < /dev/tty")
    print("\u001b[?25l")
    return if (code == 0) true to "[OK] Editor closed." else false to "[ERROR] Editor exited with code $code"
}

fun initialScriptTemplate(name: String): String = """
// @desc: $name
// @param: sample | default=value | desc=example parameter

val args: Array<String> = emptyArray()
val kv = args.mapNotNull {
    val i = it.indexOf('=')
    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
}.toMap()

println("script=$name")
println("sample=" + (kv["sample"] ?: "value"))
""".trimIndent()

fun buildQueryString(raw: String): String {
    val items = raw.split(Regex("[&\\s]+")).map { it.trim() }.filter { it.isNotBlank() }
    if (items.isEmpty()) return ""
    val pairs = items.mapNotNull {
        val idx = it.indexOf('=')
        if (idx <= 0) null else it.substring(0, idx) to it.substring(idx + 1)
    }
    if (pairs.isEmpty()) return ""
    return pairs.joinToString("&", prefix = "?") { (k, v) -> "${encode(k)}=${encode(v)}" }
}

fun runCreateFlow(
    client: HttpClient,
    baseUrl: String,
    token: String,
    oldStty: String
): String {
    val scriptName = promptLine(oldStty, "Create script name: ")
    if (scriptName.isBlank()) return "[CANCEL] Empty script name."
    val sourceMode = promptLine(oldStty, "Source mode [e=editor,f=file] (default e): ").lowercase().ifBlank { "e" }

    val content = when (sourceMode) {
        "f" -> {
            val path = promptLine(oldStty, "Source file path: ")
            if (path.isBlank()) return "[CANCEL] Empty file path."
            val f = File(path)
            if (!f.exists()) return "[ERROR] File not found: ${f.absolutePath}"
            f.readText()
        }
        else -> {
            val temp = File.createTempFile("apis-create-$scriptName-", ".hub.kts")
            val template = initialScriptTemplate(scriptName)
            temp.writeText(template)
            val (ok, msg) = openEditor(oldStty, temp)
            if (!ok) {
                temp.delete()
                return msg
            }
            val text = temp.readText()
            temp.delete()
            if (text == template) return "[CANCEL] No changes in template, skipped create."
            text
        }
    }
    if (content.isBlank()) return "[CANCEL] Empty script content."

    val (status, body) = request(client, baseUrl, token, "POST", "/scripts/${encode(scriptName)}", content)
    return "[CREATE $scriptName] HTTP $status\n$body"
}

fun runEditFlow(
    client: HttpClient,
    baseUrl: String,
    token: String,
    scriptName: String,
    oldStty: String
): String {
    val (statusGet, bodyGet) = request(client, baseUrl, token, "GET", "/scripts/${encode(scriptName)}")
    if (statusGet >= 400) return "[EDIT $scriptName] HTTP $statusGet\n$bodyGet"

    val temp = File.createTempFile("apis-edit-$scriptName-", ".hub.kts")
    temp.writeText(bodyGet)
    val (ok, msg) = openEditor(oldStty, temp)
    if (!ok) {
        temp.delete()
        return msg
    }
    val edited = temp.readText()
    temp.delete()
    if (edited == bodyGet) return "[CANCEL] No changes for $scriptName."

    val (statusPut, bodyPut) = request(client, baseUrl, token, "PUT", "/scripts/${encode(scriptName)}", edited)
    return "[EDIT $scriptName] HTTP $statusPut\n$bodyPut"
}

fun runDeleteFlow(
    client: HttpClient,
    baseUrl: String,
    token: String,
    scriptName: String,
    oldStty: String
): String {
    val confirm = promptLine(oldStty, "Delete '$scriptName'? [y/N]: ").lowercase()
    if (confirm != "y" && confirm != "yes") return "[CANCEL] Delete aborted."
    val (status, body) = request(client, baseUrl, token, "DELETE", "/scripts/${encode(scriptName)}")
    return "[DELETE $scriptName] HTTP $status\n$body"
}

fun runScriptFlow(
    client: HttpClient,
    baseUrl: String,
    token: String,
    scriptName: String,
    oldStty: String,
    initialProfile: RunProfile
): Pair<String, RunProfile?> {
    var profile = initialProfile
    var selected = 0
    var hint = "Configure request and choose Execute."

    while (true) {
        drawRunConfig(scriptName, profile, selected, hint)
        when (readKey()) {
            Key.UP -> selected = if (selected == 0) 4 else selected - 1
            Key.DOWN -> selected = if (selected == 4) 0 else selected + 1
            Key.LEFT, Key.RIGHT -> {
                if (selected == 0) {
                    profile = profile.copy(method = if (profile.method == "GET") "POST" else "GET")
                    hint = "Method set to ${profile.method}"
                }
            }
            Key.Q -> return "[CANCEL] Run aborted." to null
            Key.ENTER -> {
                when (selected) {
                    0 -> {
                        profile = profile.copy(method = if (profile.method == "GET") "POST" else "GET")
                        hint = "Method set to ${profile.method}"
                    }
                    1 -> {
                        val input = promptRawLine(oldStty, "Query args (k=v separated by '&' or space): ")
                        profile = profile.copy(queryRaw = input.trim())
                        hint = "Query updated."
                    }
                    2 -> {
                        if (profile.method == "POST") {
                            val input = promptRawLine(oldStty, "POST body (single line, blank allowed): ")
                            profile = profile.copy(body = input)
                            hint = "Body updated."
                        } else {
                            hint = "Body is ignored for GET."
                        }
                    }
                    3 -> {
                        val query = buildQueryString(profile.queryRaw)
                        val body = if (profile.method == "POST") profile.body else null
                        val (status, response) = request(client, baseUrl, token, profile.method, "/run/${encode(scriptName)}$query", body)
                        return "[RUN $scriptName] HTTP $status\n$response" to profile
                    }
                    4 -> return "[CANCEL] Run aborted." to null
                }
            }
            else -> {}
        }
    }
}

fun main(args: Array<String>) {
    val options = parseOptions(args.toList())
    val token = readToken(options)
    val client = HttpClient.newHttpClient()
    val actions = listOf("Refresh", "Show", "Run", "Meta", "Create", "Edit", "Delete", "Quit")
    val runProfiles = mutableMapOf<String, RunProfile>()
    var selectedAction = 0
    var selectedScript = 0
    var output = ""

    val oldStty = shell("stty -g < /dev/tty")
    shell("stty -echo -icanon min 1 time 0 < /dev/tty")
    print("\u001b[?25l")

    try {
        var (scripts, initMessage) = fetchScripts(client, options.baseUrl, token)
        output = initMessage
        if (selectedScript >= scripts.size) selectedScript = max(0, scripts.size - 1)

        while (true) {
            draw(options.baseUrl, scripts, selectedScript, actions, selectedAction, output)
            when (readKey()) {
                Key.UP -> if (scripts.isNotEmpty()) selectedScript = max(0, selectedScript - 1)
                Key.DOWN -> if (scripts.isNotEmpty()) selectedScript = minOf(scripts.lastIndex, selectedScript + 1)
                Key.LEFT -> selectedAction = if (selectedAction == 0) actions.lastIndex else selectedAction - 1
                Key.RIGHT -> selectedAction = if (selectedAction == actions.lastIndex) 0 else selectedAction + 1
                Key.Q -> break
                Key.ENTER -> {
                    when (actions[selectedAction]) {
                        "Refresh" -> {
                            val result = fetchScripts(client, options.baseUrl, token)
                            scripts = result.first
                            if (selectedScript >= scripts.size) selectedScript = max(0, scripts.size - 1)
                            output = result.second
                        }
                        "Show" -> {
                            output = if (scripts.isEmpty()) {
                                "No scripts."
                            } else runCatching {
                                val script = scripts[selectedScript]
                                val (status, body) = request(client, options.baseUrl, token, "GET", "/scripts/${encode(script)}")
                                "[SHOW $script] HTTP $status\n$body"
                            }.getOrElse { "[ERROR] ${it::class.simpleName}: ${it.message}" }
                        }
                        "Run" -> {
                            output = if (scripts.isEmpty()) {
                                "No scripts."
                            } else runCatching {
                                val script = scripts[selectedScript]
                                val initial = runProfiles[script] ?: RunProfile()
                                val (text, updated) = runScriptFlow(client, options.baseUrl, token, script, oldStty, initial)
                                if (updated != null) runProfiles[script] = updated
                                text
                            }.getOrElse { "[ERROR] ${it::class.simpleName}: ${it.message}" }
                        }
                        "Meta" -> {
                            output = if (scripts.isEmpty()) {
                                "No scripts."
                            } else runCatching {
                                val script = scripts[selectedScript]
                                val (status, body) = request(client, options.baseUrl, token, "GET", "/meta/${encode(script)}")
                                "[META $script] HTTP $status\n$body"
                            }.getOrElse { "[ERROR] ${it::class.simpleName}: ${it.message}" }
                        }
                        "Create" -> {
                            output = runCatching { runCreateFlow(client, options.baseUrl, token, oldStty) }
                                .getOrElse { "[ERROR] ${it::class.simpleName}: ${it.message}" }
                            val refreshed = fetchScripts(client, options.baseUrl, token)
                            scripts = refreshed.first
                            if (selectedScript >= scripts.size) selectedScript = max(0, scripts.size - 1)
                        }
                        "Edit" -> {
                            output = if (scripts.isEmpty()) {
                                "No scripts."
                            } else runCatching {
                                val script = scripts[selectedScript]
                                runEditFlow(client, options.baseUrl, token, script, oldStty)
                            }.getOrElse { "[ERROR] ${it::class.simpleName}: ${it.message}" }
                            val refreshed = fetchScripts(client, options.baseUrl, token)
                            scripts = refreshed.first
                            if (selectedScript >= scripts.size) selectedScript = max(0, scripts.size - 1)
                        }
                        "Delete" -> {
                            output = if (scripts.isEmpty()) {
                                "No scripts."
                            } else runCatching {
                                val script = scripts[selectedScript]
                                runDeleteFlow(client, options.baseUrl, token, script, oldStty)
                            }.getOrElse { "[ERROR] ${it::class.simpleName}: ${it.message}" }
                            val refreshed = fetchScripts(client, options.baseUrl, token)
                            scripts = refreshed.first
                            if (selectedScript >= scripts.size) selectedScript = max(0, scripts.size - 1)
                        }
                        "Quit" -> break
                    }
                }
                else -> {}
            }
        }
    } finally {
        shell("stty $oldStty < /dev/tty")
        print("\u001b[?25h")
        clearScreen()
    }
}

main(args)
