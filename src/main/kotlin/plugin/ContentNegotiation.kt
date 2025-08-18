@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.plugin.contentNegotiation

import moe.tachyon.quiz.dataClass.Question
import moe.tachyon.quiz.debug
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SealedClassSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule

/**
 * 针对[Question]的Answer泛型的序列化器
 */
@OptIn(InternalSerializationApi::class)
val QuestionAnswerSerializer = SealedClassSerializer(
    "QuestionAnswerSerializer",
    Any::class,
    arrayOf(Int::class, JsonElement::class, List::class),
    arrayOf(Int.serializer(), JsonElement.serializer(), ListSerializer(Int.serializer()))
)

/**
 * 用作请求/响应的json序列化/反序列化
 */
@OptIn(ExperimentalSerializationApi::class)
val contentNegotiationJson = Json()
{
    encodeDefaults = true
    prettyPrint = debug
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = true
    allowSpecialFloatingPointValues = true
    decodeEnumsCaseInsensitive = true
    allowTrailingComma = true
    classDiscriminatorMode = ClassDiscriminatorMode.NONE
    allowComments = true

    serializersModule = SerializersModule()
    {
        @OptIn(InternalSerializationApi::class)
        contextual(Any::class, QuestionAnswerSerializer)
    }
}

/**
 * 用作数据处理的json序列化/反序列化
 */
val dataJson = Json(contentNegotiationJson)
{
    prettyPrint = false
    encodeDefaults = false
}

/**
 * 用作api文档等展示的json序列化/反序列化
 */
val showJson = Json(contentNegotiationJson)
{
    prettyPrint = true
}

/**
 * 安装反序列化/序列化服务(用于处理json)
 */
fun Application.installContentNegotiation() = install(ContentNegotiation)
{
    json(contentNegotiationJson)
}