@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.plugin.contentNegotiation

import cn.org.subit.dataClass.Question
import cn.org.subit.debug
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
import kotlinx.serialization.modules.SerializersModule

/**
 * 针对[Question]的Answer泛型的序列化器
 */
@OptIn(InternalSerializationApi::class)
val QuestionAnswerSerializer = SealedClassSerializer(
    "QuestionAnswerSerializer",
    Any::class,
    arrayOf(Int::class, String::class, List::class),
    arrayOf(Int.serializer(), String.serializer(), ListSerializer(Int.serializer()))
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

    serializersModule = SerializersModule {
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