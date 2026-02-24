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
    val tokenFile: String?,
)

data class TokenInfo(
    val tokenType: String,
    val subTokenName: String?,
)

data class RunProfile(
    val method: String = "GET",
    val queryRaw: String = "",
    val body: String = "",
)

enum class Key {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    ENTER,
    SPACE,
    Q,
    OTHER,
}

enum class FocusRow {
    TARGET,
    ACTION,
    LIST,
    SYSTEM,
}

private val RESET = "\u001b[0m"
private val BOLD = "\u001b[1m"
private val DIM = "\u001b[2m"
private val CYAN = "\u001b[36m"
private val GREEN = "\u001b[32m"
private val YELLOW = "\u001b[33m"
private val RED = "\u001b[31m"
private val BG_BLUE = "\u001b[44m"
private val FG_BLACK = "\u001b[30m"
val ENV_API_BASE_URL = "HOST_API_BASE_URL"
val ENV_API_TOKEN = "HOST_API_TOKEN"

private fun ok(text: String) = "$GREEN$text$RESET"
private fun warn(text: String) = "$YELLOW$text$RESET"
private fun err(text: String) = "$RED$text$RESET"
private fun accent(text: String) = "$CYAN$text$RESET"
private fun selected(text: String) = "$BG_BLUE$FG_BLACK$BOLD$text$RESET"

fun usage(): String =
    """
Usage:
  kotlin tools/slhaf-hub-tui.kts [--base-url=http://127.0.0.1:8080] [--token=<token> | --token-file=./scripts/.host-api-token]

Layout:
  Actions:
    Target: [Scripts] [Subtokens]
    Action: [...] (depends on target)
    System: [Type] [Quit]

Keys:
  Up/Down or j/k     Focus row (Target/Action/List/System)
  Left/Right or h/l  Select item in focused row
  Enter              Execute selected Action/System
  q                  Quit

Env fallback:
  HOST_API_BASE_URL
  HOST_API_TOKEN
    """.trimIndent()

fun parseOptions(args: List<String>): Options {
    if (args.contains("--help") || args.contains("-h")) {
        println(usage())
        exitProcess(0)
    }

    var baseUrl = System.getenv(ENV_API_BASE_URL)?.trim().orEmpty().ifBlank { "http://127.0.0.1:8080" }
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
    return System.getenv(ENV_API_TOKEN)?.trim()
        ?: error("Missing token. Use --token or --token-file or HOST_API_TOKEN")
}

fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

fun request(
    client: HttpClient,
    baseUrl: String,
    token: String,
    method: String,
    path: String,
    body: String? = null,
): Pair<Int, String> {
    val reqBuilder =
        HttpRequest
            .newBuilder(URI.create("$baseUrl$path"))
            .header("Accept", "text/plain,application/json")
            .header("Authorization", "Bearer $token")

    val request =
        when (method) {
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

fun parseTokenInfo(raw: String): TokenInfo {
    val type = Regex("\"tokenType\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: "unknown"
    val subName = Regex("\"subTokenName\"\\s*:\\s*(null|\"([^\"]*)\")").find(raw)?.groupValues?.let {
        if (it[1] == "null") null else it[2]
    }
    return TokenInfo(type.lowercase(), subName)
}

fun fetchTokenInfo(client: HttpClient, baseUrl: String, token: String): Pair<TokenInfo, String> {
    val (status, body) = request(client, baseUrl, token, "GET", "/type")
    if (status >= 400) error("failed to fetch /type, HTTP $status: $body")
    val info = parseTokenInfo(body)
    val label = if (info.tokenType == "sub") "sub:${info.subTokenName ?: "-"}" else info.tokenType
    return info to "Token type: $label"
}

fun shell(cmd: String): String {
    val p = ProcessBuilder("bash", "-lc", cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return out.trim()
}

fun commandExists(cmd: String): Boolean =
    ProcessBuilder("bash", "-lc", "command -v $cmd >/dev/null 2>&1").start().waitFor() == 0

fun readKey(): Key {
    val first = System.`in`.read()
    if (first == -1) return Key.OTHER
    return when (first) {
        10, 13 -> Key.ENTER
        32 -> Key.SPACE
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
            } else {
                Key.OTHER
            }
        }
        else -> Key.OTHER
    }
}

fun clearScreen() {
    print("\u001b[2J\u001b[H")
}

fun terminalRows(): Int {
    val fromEnv = System.getenv("LINES")?.toIntOrNull()
    if (fromEnv != null && fromEnv > 0) return fromEnv
    val fromStty = shell("stty size < /dev/tty 2>/dev/null | awk '{print $1}'").trim().toIntOrNull()
    if (fromStty != null && fromStty > 0) return fromStty
    return 40
}

fun colorizeStatusLine(line: String): String =
    when {
        line.startsWith("[ERROR]") || line.startsWith("[HTTP 4") || line.startsWith("[HTTP 5") -> err(line)
        line.startsWith("Loaded") || line.contains("HTTP 200") || line.startsWith("[RUN") || line.startsWith("[SHOW") || line.startsWith("[META") || line.startsWith("[CREATE") || line.startsWith("[EDIT") || line.startsWith("[DELETE") || line.startsWith("[SUB") || line.startsWith("Token type:") -> ok(line)
        line.startsWith("No scripts.") || line.startsWith("[HTTP 3") || line.startsWith("[CANCEL]") -> warn(line)
        else -> line
    }

fun prettyPrintJsonOrKeep(raw: String): String {
    val text = raw.trim()
    if (!(text.startsWith("{") || text.startsWith("["))) return raw
    return runCatching { prettyJson(text) }.getOrElse { raw }
}

fun prettyJson(input: String): String {
    val sb = StringBuilder(input.length + 32)
    var indent = 0
    var inString = false
    var escaping = false

    fun appendIndent() {
        repeat(indent) { sb.append("  ") }
    }

    input.forEach { ch ->
        if (inString) {
            sb.append(ch)
            if (escaping) {
                escaping = false
            } else if (ch == '\\') {
                escaping = true
            } else if (ch == '"') {
                inString = false
            }
            return@forEach
        }

        when (ch) {
            ' ', '\n', '\r', '\t' -> {
                // ignore insignificant whitespace outside strings
            }
            '"' -> {
                inString = true
                sb.append(ch)
            }
            '{', '[' -> {
                sb.append(ch).append('\n')
                indent += 1
                appendIndent()
            }
            '}', ']' -> {
                sb.append('\n')
                indent = (indent - 1).coerceAtLeast(0)
                appendIndent()
                sb.append(ch)
            }
            ',' -> {
                sb.append(ch).append('\n')
                appendIndent()
            }
            ':' -> sb.append(": ")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

fun responseText(tag: String, status: Int, body: String): String =
    "[$tag] HTTP $status\n${prettyPrintJsonOrKeep(body)}"

fun drawRow(label: String, items: List<String>, selectedIdx: Int, focused: Boolean, highlightOnlyWhenFocused: Boolean = false) {
    print("  ${if (focused) selected(label) else "$DIM$label$RESET"}: ")
    items.forEachIndexed { idx, name ->
        val shouldHighlight = idx == selectedIdx && (!highlightOnlyWhenFocused || focused)
        if (shouldHighlight) print(selected(" $name ")) else print("[${accent(name)}] ")
    }
    println()
}

fun draw(
    baseUrl: String,
    targetOptions: List<String>,
    targetIdx: Int,
    actionOptions: List<String>,
    actionIdx: Int,
    systemOptions: List<String>,
    systemIdx: Int,
    listTitle: String,
    listItems: List<String>,
    listIdx: Int,
    focus: FocusRow,
    output: String,
) {
    clearScreen()
    println("${accent("API TUI")}  ${DIM}base=$baseUrl$RESET")
    println("${DIM}Keys: Up/Down focus row | Left/Right select | Enter execute | q quit$RESET")
    println()

    println("${BOLD}Actions:$RESET")
    drawRow("Target", targetOptions, targetIdx, focus == FocusRow.TARGET)
    drawRow("Action", actionOptions, actionIdx, focus == FocusRow.ACTION)
    drawRow("System", systemOptions, systemIdx, focus == FocusRow.SYSTEM, highlightOnlyWhenFocused = true)

    println()
    println("${BOLD}$listTitle:$RESET ${if (focus == FocusRow.LIST) selected(" selected ") else ""}")
    if (listItems.isEmpty()) {
        println("  ${DIM}(no items)$RESET")
    } else {
        print("  ")
        listItems.forEachIndexed { idx, name ->
            if (idx == listIdx) print(selected(" $name ")) else print("[${accent(name)}] ")
        }
        println()
    }

    println()
    println("${BOLD}Output:$RESET")
    val reservedRows = 14
    val outputRows = max(1, terminalRows() - reservedRows)
    val lines = output.lines()
    val bodyLooksJson = output.contains("\n{") || output.contains("\n[")
    val visible = if (bodyLooksJson) lines.take(outputRows) else lines.takeLast(outputRows)
    visible.forEach { println(colorizeStatusLine(it)) }
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
        "Cancel",
    )
    rows.forEachIndexed { idx, row ->
        if (idx == selected) println(" ${selected("> $row")}") else println("   $row")
    }

    println()
    println("${BOLD}Hint:$RESET ${colorizeStatusLine(hint)}")
}

fun drawScriptPicker(title: String, scripts: List<String>, cursor: Int, selectedScripts: Set<String>, hint: String) {
    clearScreen()
    println("${accent(title)}")
    println("${DIM}Up/Down select | Space toggle | Enter submit | q cancel$RESET")
    println()

    if (scripts.isEmpty()) {
        println("  ${DIM}(no scripts)$RESET")
    } else {
        scripts.forEachIndexed { idx, name ->
            val mark = if (selectedScripts.contains(name)) "[x]" else "[ ]"
            val row = "$mark $name"
            if (idx == cursor) println(" ${selected("> $row")}") else println("   $row")
        }
    }

    println()
    println("${BOLD}Selected:${RESET} ${selectedScripts.sorted().joinToString(", ").ifBlank { "(none)" }}")
    println("${BOLD}Hint:${RESET} ${colorizeStatusLine(hint)}")
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

fun fetchSubtokens(client: HttpClient, baseUrl: String, token: String): Pair<List<String>, String> {
    return try {
        val (status, body) = request(client, baseUrl, token, "GET", "/subtokens")
        if (status >= 400) return emptyList<String>() to "[HTTP $status]\n$body"
        val names = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
        names to "Loaded ${names.size} subtoken(s)."
    } catch (t: Throwable) {
        emptyList<String>() to "[ERROR] ${t::class.simpleName}: ${t.message}"
    }
}

fun fetchSubtokenScripts(client: HttpClient, baseUrl: String, token: String, name: String): Set<String> {
    val (status, body) = request(client, baseUrl, token, "GET", "/subtokens/${encode(name)}")
    if (status >= 400) error("failed to load subtoken '$name', HTTP $status: $body")

    val scriptsBlock = Regex("\"scripts\"\\s*:\\s*\\[(.*?)]", setOf(RegexOption.DOT_MATCHES_ALL)).find(body)?.groupValues?.get(1) ?: ""
    return Regex("\"([^\"]+)\"").findAll(scriptsBlock).map { it.groupValues[1] }.toSet()
}

fun chooseEditor(): String? {
    val env = System.getenv("EDITOR")?.trim()
    if (!env.isNullOrBlank()) return env
    return listOf("nvim", "vim", "nano").firstOrNull { commandExists(it) }
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
    val cmd = "$editor \"${file.absolutePath.replace("\"", "\\\"")}\""
    val process = ProcessBuilder("bash", "-lc", cmd).inheritIO().start()
    val code = process.waitFor()
    shell("stty -echo -icanon min 1 time 0 < /dev/tty")
    print("\u001b[?25l")
    return if (code == 0) true to "[OK] Editor closed." else false to "[ERROR] Editor exited with code $code"
}

fun initialScriptTemplate(name: String): String =
    """
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

fun runCreateFlow(client: HttpClient, baseUrl: String, token: String, oldStty: String): String {
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
    return responseText("CREATE $scriptName", status, body)
}

fun runEditFlow(client: HttpClient, baseUrl: String, token: String, scriptName: String, oldStty: String): String {
    val (statusGet, bodyGet) = request(client, baseUrl, token, "GET", "/scripts/${encode(scriptName)}")
    if (statusGet >= 400) return responseText("EDIT $scriptName", statusGet, bodyGet)

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
    return responseText("EDIT $scriptName", statusPut, bodyPut)
}

fun runDeleteFlow(client: HttpClient, baseUrl: String, token: String, scriptName: String, oldStty: String): String {
    val confirm = promptLine(oldStty, "Delete '$scriptName'? [y/N]: ").lowercase()
    if (confirm != "y" && confirm != "yes") return "[CANCEL] Delete aborted."
    val (status, body) = request(client, baseUrl, token, "DELETE", "/scripts/${encode(scriptName)}")
    return responseText("DELETE $scriptName", status, body)
}

fun pickScriptsFlow(title: String, allScripts: List<String>, preselected: Set<String>): Set<String>? {
    if (allScripts.isEmpty()) return emptySet()

    var cursor = 0
    val selected = preselected.toMutableSet()
    var hint = "Space to toggle, Enter to submit."

    while (true) {
        drawScriptPicker(title, allScripts, cursor, selected, hint)
        when (readKey()) {
            Key.UP -> cursor = if (cursor == 0) allScripts.lastIndex else cursor - 1
            Key.DOWN -> cursor = if (cursor == allScripts.lastIndex) 0 else cursor + 1
            Key.SPACE -> {
                val script = allScripts[cursor]
                if (!selected.add(script)) selected.remove(script)
                hint = "Toggled '$script'."
            }
            Key.ENTER -> return selected.toSet()
            Key.Q -> return null
            else -> {}
        }
    }
}

fun runSubTokenListFlow(client: HttpClient, baseUrl: String, token: String): String {
    val (status, body) = request(client, baseUrl, token, "GET", "/subtokens")
    return responseText("SUB-LIST", status, body)
}

fun runSubTokenShowFlow(client: HttpClient, baseUrl: String, token: String, selectedName: String?, oldStty: String): String {
    val name = selectedName ?: promptLine(oldStty, "Subtoken name: ")
    if (name.isBlank()) return "[CANCEL] Empty subtoken name."
    val (status, body) = request(client, baseUrl, token, "GET", "/subtokens/${encode(name)}")
    return responseText("SUB-SHOW $name", status, body)
}

fun runSubTokenCreateFlow(
    client: HttpClient,
    baseUrl: String,
    token: String,
    oldStty: String,
    allScripts: List<String>,
): String {
    val name = promptLine(oldStty, "Subtoken name: ")
    if (name.isBlank()) return "[CANCEL] Empty subtoken name."

    val picked = pickScriptsFlow("Select scripts for new subtoken: $name", allScripts, emptySet())
        ?: return "[CANCEL] Subtoken create canceled."

    val body = picked.joinToString("\n")
    val (status, content) = request(client, baseUrl, token, "POST", "/subtokens/${encode(name)}", body)
    return responseText("SUB-CREATE $name", status, content)
}

fun runSubTokenUpdateFlow(
    client: HttpClient,
    baseUrl: String,
    token: String,
    selectedName: String?,
    oldStty: String,
    allScripts: List<String>,
): String {
    val name = selectedName ?: promptLine(oldStty, "Subtoken name to update: ")
    if (name.isBlank()) return "[CANCEL] Empty subtoken name."

    val currentScripts = fetchSubtokenScripts(client, baseUrl, token, name)
    val picked = pickScriptsFlow("Select scripts for subtoken: $name", allScripts, currentScripts)
        ?: return "[CANCEL] Subtoken update canceled."

    val body = picked.joinToString("\n")
    val (status, content) = request(client, baseUrl, token, "PUT", "/subtokens/${encode(name)}", body)
    return responseText("SUB-UPDATE $name", status, content)
}

fun runSubTokenDeleteFlow(client: HttpClient, baseUrl: String, token: String, selectedName: String?, oldStty: String): String {
    val name = selectedName ?: promptLine(oldStty, "Subtoken name to delete: ")
    if (name.isBlank()) return "[CANCEL] Empty subtoken name."
    val confirm = promptLine(oldStty, "Delete subtoken '$name'? [y/N]: ").lowercase()
    if (confirm != "y" && confirm != "yes") return "[CANCEL] Delete aborted."
    val (status, body) = request(client, baseUrl, token, "DELETE", "/subtokens/${encode(name)}")
    return responseText("SUB-DELETE $name", status, body)
}

fun runScriptFlow(
    client: HttpClient,
    baseUrl: String,
    token: String,
    scriptName: String,
    oldStty: String,
    initialProfile: RunProfile,
): Pair<String, RunProfile?> {
    var profile = initialProfile
    var selected = 0
    var hint = "Configure request and choose Execute."

    while (true) {
        drawRunConfig(scriptName, profile, selected, hint)
        when (readKey()) {
            Key.UP -> selected = if (selected == 0) 4 else selected - 1
            Key.DOWN -> selected = if (selected == 4) 0 else selected + 1
            Key.LEFT, Key.RIGHT -> if (selected == 0) {
                profile = profile.copy(method = if (profile.method == "GET") "POST" else "GET")
                hint = "Method set to ${profile.method}"
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
                        return responseText("RUN $scriptName", status, response) to profile
                    }
                    4 -> return "[CANCEL] Run aborted." to null
                }
            }
            else -> {}
        }
    }
}

fun targetOptions(tokenInfo: TokenInfo): List<String> =
    if (tokenInfo.tokenType == "sub") listOf("Scripts") else listOf("Scripts", "Subtokens")

fun actionOptions(tokenInfo: TokenInfo, target: String): List<String> =
    when (target) {
        "Subtokens" -> listOf("Refresh", "List", "Show", "Create", "Update", "Delete")
        else -> {
            if (tokenInfo.tokenType == "sub") {
                listOf("Refresh", "Run", "Meta")
            } else {
                listOf("Refresh", "Show", "Run", "Meta", "Create", "Edit", "Delete")
            }
        }
    }

fun main(args: Array<String>) {
    val options = parseOptions(args.toList())
    val token = readToken(options)
    val client = HttpClient.newHttpClient()

    val (tokenInfo, tokenInfoText) =
        runCatching { fetchTokenInfo(client, options.baseUrl, token) }
            .getOrElse { t ->
                val reason = t.message?.ifBlank { null } ?: t::class.simpleName ?: "unknown"
                System.err.println("[WARN] Backend unavailable at ${options.baseUrl}, TUI exited. reason=$reason")
                return
            }

    var scripts = emptyList<String>()
    var subtokens = emptyList<String>()
    var output = tokenInfoText

    var focus = FocusRow.ACTION
    var targetIdx = 0
    var actionIdx = 0
    var systemIdx = 0
    var listIdx = 0

    val runProfiles = mutableMapOf<String, RunProfile>()
    val systemOptions = listOf("Type", "Quit")

    val oldStty = shell("stty -g < /dev/tty")
    shell("stty -echo -icanon min 1 time 0 < /dev/tty")
    print("\u001b[?25l")

    try {
        val initScripts = fetchScripts(client, options.baseUrl, token)
        scripts = initScripts.first
        output += "\n" + initScripts.second

        if (tokenInfo.tokenType == "root") {
            val initSubs = fetchSubtokens(client, options.baseUrl, token)
            subtokens = initSubs.first
            output += "\n" + initSubs.second
        }

        while (true) {
            val targets = targetOptions(tokenInfo)
            if (targetIdx > targets.lastIndex) targetIdx = 0

            val currentTarget = targets[targetIdx]
            val actions = actionOptions(tokenInfo, currentTarget)
            if (actionIdx > actions.lastIndex) actionIdx = 0

            val listTitle = currentTarget
            val listItems = if (currentTarget == "Subtokens") subtokens else scripts
            if (listIdx > listItems.lastIndex) listIdx = max(0, listItems.lastIndex)

            draw(
                baseUrl = options.baseUrl,
                targetOptions = targets,
                targetIdx = targetIdx,
                actionOptions = actions,
                actionIdx = actionIdx,
                systemOptions = systemOptions,
                systemIdx = systemIdx,
                listTitle = listTitle,
                listItems = listItems,
                listIdx = listIdx,
                focus = focus,
                output = output,
            )

            when (readKey()) {
                Key.UP -> {
                    focus = when (focus) {
                        FocusRow.TARGET -> FocusRow.LIST
                        FocusRow.ACTION -> FocusRow.TARGET
                        FocusRow.SYSTEM -> FocusRow.ACTION
                        FocusRow.LIST -> FocusRow.SYSTEM
                    }
                }
                Key.DOWN -> {
                    focus = when (focus) {
                        FocusRow.TARGET -> FocusRow.ACTION
                        FocusRow.ACTION -> FocusRow.SYSTEM
                        FocusRow.SYSTEM -> FocusRow.LIST
                        FocusRow.LIST -> FocusRow.TARGET
                    }
                }
                Key.LEFT -> {
                    when (focus) {
                        FocusRow.TARGET -> {
                            targetIdx = if (targetIdx == 0) targets.lastIndex else targetIdx - 1
                            actionIdx = 0
                            listIdx = 0
                        }
                        FocusRow.ACTION -> actionIdx = if (actionIdx == 0) actions.lastIndex else actionIdx - 1
                        FocusRow.LIST -> if (listItems.isNotEmpty()) listIdx = if (listIdx == 0) listItems.lastIndex else listIdx - 1
                        FocusRow.SYSTEM -> systemIdx = if (systemIdx == 0) systemOptions.lastIndex else systemIdx - 1
                    }
                }
                Key.RIGHT -> {
                    when (focus) {
                        FocusRow.TARGET -> {
                            targetIdx = if (targetIdx == targets.lastIndex) 0 else targetIdx + 1
                            actionIdx = 0
                            listIdx = 0
                        }
                        FocusRow.ACTION -> actionIdx = if (actionIdx == actions.lastIndex) 0 else actionIdx + 1
                        FocusRow.LIST -> if (listItems.isNotEmpty()) listIdx = if (listIdx == listItems.lastIndex) 0 else listIdx + 1
                        FocusRow.SYSTEM -> systemIdx = if (systemIdx == systemOptions.lastIndex) 0 else systemIdx + 1
                    }
                }
                Key.Q -> break
                Key.ENTER -> {
                    if (focus == FocusRow.SYSTEM) {
                        when (systemOptions[systemIdx]) {
                            "Type" -> {
                                output = runCatching {
                                    val (info, text) = fetchTokenInfo(client, options.baseUrl, token)
                                    val suffix = if (info.tokenType == "sub") "\nsubToken=${info.subTokenName ?: "-"}" else ""
                                    "$text$suffix"
                                }.getOrElse { "[ERROR] ${it::class.simpleName}: ${it.message}" }
                            }
                            "Quit" -> break
                        }
                    } else {
                        val selectedItem = listItems.getOrNull(listIdx)
                        output = runCatching {
                            when (currentTarget) {
                                "Subtokens" -> {
                                    when (actions[actionIdx]) {
                                        "Refresh", "List" -> {
                                            val res = fetchSubtokens(client, options.baseUrl, token)
                                            subtokens = res.first
                                            listIdx = if (subtokens.isEmpty()) 0 else minOf(listIdx, subtokens.lastIndex)
                                            "[SUB-LIST]\n${res.second}"
                                        }
                                        "Show" -> runSubTokenShowFlow(client, options.baseUrl, token, selectedItem, oldStty)
                                        "Create" -> {
                                            val text = runSubTokenCreateFlow(client, options.baseUrl, token, oldStty, scripts)
                                            val res = fetchSubtokens(client, options.baseUrl, token)
                                            subtokens = res.first
                                            text + "\n" + res.second
                                        }
                                        "Update" -> {
                                            val text = runSubTokenUpdateFlow(client, options.baseUrl, token, selectedItem, oldStty, scripts)
                                            val res = fetchSubtokens(client, options.baseUrl, token)
                                            subtokens = res.first
                                            text + "\n" + res.second
                                        }
                                        "Delete" -> {
                                            val text = runSubTokenDeleteFlow(client, options.baseUrl, token, selectedItem, oldStty)
                                            val res = fetchSubtokens(client, options.baseUrl, token)
                                            subtokens = res.first
                                            listIdx = if (subtokens.isEmpty()) 0 else minOf(listIdx, subtokens.lastIndex)
                                            text + "\n" + res.second
                                        }
                                        else -> "[ERROR] Unsupported subtoken action"
                                    }
                                }
                                else -> {
                                    when (actions[actionIdx]) {
                                        "Refresh" -> {
                                            val res = fetchScripts(client, options.baseUrl, token)
                                            scripts = res.first
                                            listIdx = if (scripts.isEmpty()) 0 else minOf(listIdx, scripts.lastIndex)
                                            res.second
                                        }
                                        "Show" -> {
                                            val script = selectedItem ?: return@runCatching "No scripts."
                                            val (status, body) = request(client, options.baseUrl, token, "GET", "/scripts/${encode(script)}")
                                            responseText("SHOW $script", status, body)
                                        }
                                        "Run" -> {
                                            val script = selectedItem ?: return@runCatching "No scripts."
                                            val initial = runProfiles[script] ?: RunProfile()
                                            val (text, updated) = runScriptFlow(client, options.baseUrl, token, script, oldStty, initial)
                                            if (updated != null) runProfiles[script] = updated
                                            text
                                        }
                                        "Meta" -> {
                                            val script = selectedItem ?: return@runCatching "No scripts."
                                            val (status, body) = request(client, options.baseUrl, token, "GET", "/meta/${encode(script)}")
                                            responseText("META $script", status, body)
                                        }
                                        "Create" -> {
                                            val text = runCreateFlow(client, options.baseUrl, token, oldStty)
                                            val res = fetchScripts(client, options.baseUrl, token)
                                            scripts = res.first
                                            text + "\n" + res.second
                                        }
                                        "Edit" -> {
                                            val script = selectedItem ?: return@runCatching "No scripts."
                                            val text = runEditFlow(client, options.baseUrl, token, script, oldStty)
                                            val res = fetchScripts(client, options.baseUrl, token)
                                            scripts = res.first
                                            text + "\n" + res.second
                                        }
                                        "Delete" -> {
                                            val script = selectedItem ?: return@runCatching "No scripts."
                                            val text = runDeleteFlow(client, options.baseUrl, token, script, oldStty)
                                            val res = fetchScripts(client, options.baseUrl, token)
                                            scripts = res.first
                                            listIdx = if (scripts.isEmpty()) 0 else minOf(listIdx, scripts.lastIndex)
                                            text + "\n" + res.second
                                        }
                                        else -> "[ERROR] Unsupported script action"
                                    }
                                }
                            }
                        }.getOrElse { "[ERROR] ${it::class.simpleName}: ${it.message}" }
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
