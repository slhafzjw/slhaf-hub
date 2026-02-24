import java.time.LocalDateTime

// @desc: Demo greeting API
// @param: name | default=world | desc=Name to greet
// @param: upper | default=false | desc=Uppercase output

val args: Array<String> = emptyArray()
val kv = args.mapNotNull {
    val idx = it.indexOf('=')
    if (idx <= 0) null else it.substring(0, idx) to it.substring(idx + 1)
}.toMap()

val name = kv["name"] ?: "world"
val upper = (kv["upper"] ?: "false").toBoolean()
val message = "Hello, $name @ ${LocalDateTime.now()}"

println(if (upper) message.uppercase() else message)
