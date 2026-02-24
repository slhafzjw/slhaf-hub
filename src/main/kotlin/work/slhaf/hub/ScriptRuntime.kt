package work.slhaf.hub

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
)

data class ScriptRequestContext(
    val args: List<String> = emptyList(),
    val body: String? = null
)
