package moe.tachyon.quiz.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import org.koin.mp.KoinPlatformTools
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.*

@Suppress("unused")
fun String?.toUUIDOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
inline fun <reified T: Enum<T>> String?.toEnumOrNull(): T? =
    this?.runCatching { contentNegotiationJson.decodeFromString<T>(this) }?.getOrNull()
fun getKoin() = KoinPlatformTools.defaultContext().get()

open class LineOutputStream(private val line: (String) -> Unit): OutputStream()
{
    private val arrayOutputStream = ByteArrayOutputStream()
    override fun write(b: Int)
    {
        if (b == '\n'.code)
        {
            val str: String
            synchronized(arrayOutputStream)
            {
                str = arrayOutputStream.toString()
                arrayOutputStream.reset()
            }
            runCatching { line(str) }
        }
        else
        {
            arrayOutputStream.write(b)
        }
    }
}

open class LinePrintStream(private val line: (String) -> Unit): PrintStream(LineOutputStream(line))
{
    override fun println(x: Any?) = x.toString().split('\n').forEach(line)

    override fun println() = println("" as Any?)
    override fun println(x: Boolean) = println(x as Any?)
    override fun println(x: Char) = println(x as Any?)
    override fun println(x: Int) = println(x as Any?)
    override fun println(x: Long) = println(x as Any?)
    override fun println(x: Float) = println(x as Any?)
    override fun println(x: Double) = println(x as Any?)
    override fun println(x: CharArray) = println(x.joinToString("") as Any?)
    override fun println(x: String?) = println(x as Any?)
}

fun richTextToString(richText: JsonElement): String
{
    return when (richText)
    {
        is JsonArray   -> richText.joinToString("", transform = ::richTextToString)
        is JsonPrimitive -> richText.content
        is JsonObject  ->
        {
            val text = richText["text"]
            if (text != null) return richTextToString(text)
            val content = richText["content"]
            if (content != null) return richTextToString(content)
            val children = richText["children"]
            if (children != null) return richTextToString(children)
            return ""
        }
    }
}