package eth.verificat.ensupdater

import kotlin.system.exitProcess

fun error(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
fun <T> retry(times: Int = 7, block: () -> T?): T? {
    (0..times).forEach { _ ->
        val result = block.invoke()
        if (result != null) return result
    }
    return null
}