// @desc: hello
// @timeout: 10s
// @param: name   | default=world | desc=hello <name> | required=false
// @param: upper  | default=false | desc=upper text   | required=true
import java.time.LocalDateTime

val args: Array<String> = emptyArray()
val kv =
    args
        .mapNotNull {
            val idx = it.indexOf('=')
            if (idx <= 0) null else it.substring(0, idx) to it.substring(idx + 1)
        }.toMap()

val name = kv["name"] ?: "world"
val upper = (kv["upper"]!!).toBoolean()
val message = "Hello, $name @ ${LocalDateTime.now()}"

println(if (upper) message.uppercase() else message)
