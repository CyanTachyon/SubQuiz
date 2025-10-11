@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.utils.ai.internal.videoGeneration

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.decodeBase64Bytes
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.utils.Either
import moe.tachyon.quiz.utils.ktorClientEngineFactory

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class Resquest(
    val model: String,
    val prompt: String,
    @SerialName("image_size")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val imageSize: String? = null,
    @SerialName("negative_prompt")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val negativePrompt: String? = null,
    @SerialName("image")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val image: String? = null
)

@Serializable
private data class StatusResponse(
    val status: String,
    val reason: String? = null,
    val results: Results? = null,
)
{
    @Serializable
    data class Results(
        val videos: List<Video>
    )

    @Serializable
    data class Video(
        val url: String
    )
}

private val client = HttpClient(ktorClientEngineFactory)
{
    engine()
    {
        dispatcher = Dispatchers.IO
        requestTimeout = 0
    }
    install(ContentNegotiation)
    {
        json(contentNegotiationJson)
    }
}

suspend fun sendVideoGenerationRequest(
    prompt: String,
    negativePrompt: String?,
    image: String?,
    imageSize: String?,
): Pair<String, String>
{
    val parm = Resquest(
        model = if (image == null) aiConfig.videoGenerator.t2vModel else aiConfig.videoGenerator.i2vModel,
        prompt = prompt,
        negativePrompt = negativePrompt,
        image = image,
        imageSize = imageSize,
    )
    val token = aiConfig.videoGenerator.key.random()
    val res = client.post(aiConfig.videoGenerator.submitUrl)
    {
        setBody(parm)
        contentType(ContentType.Application.Json)
        bearerAuth(token)
    }.bodyAsText()
    val json = try
    {
        contentNegotiationJson.parseToJsonElement(res)
    }
    catch (_: Throwable)
    {
        error("视频生成请求失败，无法解析返回值：$res")
    }
    return (json.jsonObject["requestId"]?.jsonPrimitive?.content ?: error("视频生成请求失败，无法解析返回值：$res")) to token
}

suspend fun getVideoGenerationStatus(requestId: String, token: String): Either<ByteArray, String>?
{
    val res = client.post(aiConfig.videoGenerator.statusUrl)
    {
        setBody(mapOf("requestId" to requestId))
        contentType(ContentType.Application.Json)
        bearerAuth(token)
    }.bodyAsText()
    val json = try
    {
        contentNegotiationJson.parseToJsonElement(res)
    }
    catch (_: Throwable)
    {
        error("视频生成状态请求失败，无法解析返回值：$res")
    }
    val statusResponse = try
    {
        contentNegotiationJson.decodeFromJsonElement<StatusResponse>(json)
    }
    catch (_: Throwable)
    {
        error("视频生成状态请求失败，无法解析返回值：$res")
    }
    return when (statusResponse.status.lowercase())
    {
        "succeed" -> statusResponse.results?.videos?.firstOrNull()?.url?.let { url ->
            if (url.startsWith("data:"))
            {
                val base64 = url.substringAfter("base64,", "")
                val videoData = base64.decodeBase64Bytes()
                return Either.Left(videoData)
            }
            Either.Left(client.get(url).bodyAsBytes())
        } ?: error("视频生成失败，无法获取视频链接：$res")
        "failed"    -> Either.Right(statusResponse.reason ?: "视频生成失败，未知错误")
        else        -> null
    }
}