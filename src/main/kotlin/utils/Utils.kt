@file:Suppress("unused")

package moe.tachyon.quiz.utils

import com.charleskorn.kaml.*
import kotlinx.serialization.json.*
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import org.koin.mp.KoinPlatformTools
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.*
import javax.imageio.ImageIO

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

/**
 * 判断文本内容是否不超过x个中文字符或2x个英文字符
 */
fun isWithinChineseCharLimit(text: String, limit: Int): Boolean
{
    var count = 0
    for (ch in text)
    {
        count += if (ch.code in 0..127) 1 else 2
        if (count > limit * 2) return false
    }
    return true
}

fun YamlNode.toJsonElement(): JsonElement
{
    return when (this)
    {
        is YamlMap ->
        {
            val map = mutableMapOf<String, JsonElement>()
            for ((key, value) in this.entries)
            {
                val keyStr = key.toJsonElement().jsonPrimitive.content
                map[keyStr] = value.toJsonElement()
            }
            JsonObject(map)
        }
        is YamlList -> JsonArray(this.items.map { it.toJsonElement() })
        is YamlNull -> JsonNull
        is YamlScalar ->
        {
            runCatching { return JsonPrimitive(this.toBoolean()) }
            runCatching { return JsonPrimitive(this.toLong()) }
            runCatching { return JsonPrimitive(this.toDouble()) }
            return JsonPrimitive(this.content)
        }
        is YamlTaggedNode -> this.innerNode.toJsonElement()
    }
}

fun JsonElement.toYamlNode(): YamlNode =
    Yaml.default.parseToYamlNode(this.toString())

fun ByteArray.toJpegBytes(): ByteArray =
    ByteArrayOutputStream().also()
    {
        ImageIO.write(ImageIO.read(this.inputStream()).withoutAlpha(), "jpeg", it)
    }.toByteArray()

fun BufferedImage.withoutAlpha(): BufferedImage
{
    val rImg = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = rImg.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, rImg.width, rImg.height)
    graphics.drawImage(this, 0, 0, null)
    graphics.dispose()
    return rImg
}