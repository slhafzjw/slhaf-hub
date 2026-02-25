package work.slhaf.hub

import kotlin.script.experimental.annotations.KotlinScript

@KotlinScript(fileExtension = "hub.kts")
abstract class SimpleScript(
    val hostArgs: Array<String> = emptyArray(),
)
