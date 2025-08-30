@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.utils.ai.internal.imageGeneration

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.utils.ktorClientEngineFactory
import java.awt.image.BufferedImage
import java.net.URL
import javax.imageio.ImageIO

private val logger = SubQuizLogger.getLogger()

@Serializable
@OptIn(ExperimentalSerializationApi::class)
private data class Request(
    val model: String,
    val prompt: String,
    @SerialName("image_size")
    val imageSize: String,
    @SerialName("batch_size")
    val batchSize: Int = 1,
    @SerialName("num_inference_steps")
    val inferenceSteps: Int = 20,
    @SerialName("guidance_scale")
    val guidanceScale: Double = 7.5,
    @SerialName("seed")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val seed: Long? = null,
    @SerialName("negative_prompt")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val negativePrompt: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val image: String? = null,
)

@Serializable
private data class Response(
    val images: List<Image>,
    val timings: Timings,
    val seed: Long,
)
{
    @Serializable
    data class Image(
        val url: String,
    )

    @Serializable
    data class Timings(
        val inference: Double,
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

suspend fun sendImageGenerationRequest(
    url: String,
    key: String,
    model: String,
    prompt: String,
    imageSize: String = "512x512",
    batchSize: Int = 1,
    inferenceSteps: Int = 20,
    guidanceScale: Double = 7.5,
    seed: Long? = null,
    negativePrompt: String? = null,
    image: String? = null,
): List<BufferedImage>
{
    val request = Request(
        model = model,
        prompt = prompt,
        imageSize = imageSize,
        batchSize = batchSize,
        inferenceSteps = inferenceSteps,
        guidanceScale = guidanceScale,
        seed = seed,
        negativePrompt = negativePrompt,
        image = image,
    )

    val response = client.post(url)
    {
        bearerAuth(key)
        contentType(ContentType.Application.Json)
        setBody(request)
        accept(ContentType.Any)
    }.bodyAsText()
    val json = runCatching()
    {
        contentNegotiationJson.decodeFromString<JsonElement>(response)
    }.onFailure()
    {
        logger.warning("Ai response is not a valid JSON: $response")
    }.getOrThrow()

    val res = runCatching()
    {
        contentNegotiationJson.decodeFromJsonElement<Response>(json)
    }.onFailure()
    {
        logger.warning("Failed to decode image generation response: $response")
    }.getOrThrow()

    val urls = res.images.map { it.url }.map(::URL)
    val images = urls.map { ImageIO.read(it) }
    return images
}

suspend fun sendImageGenerationRequest(
    prompt: String,
    imageSize: String = "512x512",
    batchSize: Int = 1,
    inferenceSteps: Int = 20,
    guidanceScale: Double = 7.5,
    seed: Long? = null,
    negativePrompt: String? = null,
    image: String? = null,
): List<BufferedImage> = aiConfig.imageGenerator.semaphore.withPermit()
{
    sendImageGenerationRequest(
        url = aiConfig.imageGenerator.url,
        key = aiConfig.imageGenerator.key.random(),
        model = aiConfig.imageGenerator.model,
        prompt = prompt,
        imageSize = imageSize,
        batchSize = batchSize,
        inferenceSteps = inferenceSteps,
        guidanceScale = guidanceScale,
        seed = seed,
        negativePrompt = negativePrompt,
        image = image,
    )
}