package work.slhaf.hub

enum class ScriptResponseType {
    TEXT,
    JSON,
    HTML,
}

data class ScriptParamDefinition(
    val name: String,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val description: String? = null
)

data class ScriptMetadata(
    val description: String? = null,
    val params: List<ScriptParamDefinition> = emptyList(),
    val timeoutMs: Long = 10_000L,
    val responseType: ScriptResponseType = ScriptResponseType.TEXT,
)

data class ScriptRequestContext(
    val args: List<String> = emptyList(),
    val body: String? = null
)
