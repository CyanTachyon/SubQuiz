package moe.tachyon.quiz.config

import com.charleskorn.kaml.YamlComment
import com.charleskorn.kaml.YamlMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AiConfig(
    @YamlComment("AI请求的超时时间，单位为毫秒，仅限非流式请求")
    val timeout: Long = 2 * 60 * 1_000,
    @YamlComment("AI服务的重试次数")
    val retry: Int = 3,
    val webSearchKey: List<String> = listOf("your web search key"),
    val answerChecker: String = "ds-r1",
    val chats: List<ChatModel> = listOf(ChatModel("ds-r1")),
    val image: String = "qwen-vl",
    val checker: String = "ds-r1-qwen3-8b",
    val translator: String = "ds-r1-qwen3-8b",
    val chatNamer: String = "ds-r1-qwen3-8b",
    val essayCorrector: String = "ds-r1",
    val contextCompressor: String = "ds-r1-qwen3-8b",
    val imageGenerator: Model = Model(),
    val imageEditor: Model = Model(),
    val videoGenerator: VideoModel = VideoModel(),

    val embedding: Model = Model(),
    val reranker: Model = Model(),
    val models: Map<String, LlmModel> = mapOf(
        "ds-r1" to LlmModel(model = "deepseek-reasoner"),
        "qwen-vl" to LlmModel(url = "https://api.siliconflow.cn/v1/chat/completions", model = "Qwen/Qwen2.5-VL-72B-Instruct", imageable = true),
        "ds-r1-qwen3-8b" to LlmModel(url = "https://api.siliconflow.cn/v1/chat/completions", model = "deepseek-ai/DeepSeek-R1-0528-Qwen3-8B"),
    ),
)
{
    val answerCheckerModel get() = models[answerChecker]!!
    val imageModel get() = models[image]!!
    val checkerModel get() = models[checker]!!
    val translatorModel get() = models[translator]!!
    val chatNamerModel get() = models[chatNamer]!!
    val contextCompressorModel get() = models[contextCompressor]!!
    val essayCorrectorModel get() = models[essayCorrector]!!

    @Serializable
    data class LlmModel(
        val url: String = "https://api.deepseek.com/chat/completions",
        val model: String = "deepseek-reasoner",
        val maxConcurrency: Int = 50,
        val imageable: Boolean = false,
        val toolable: Boolean = false,
        @YamlComment("思考预算，单位为token，null表示不设置")
        @SerialName("thinking_budget")
        val thinkingBudget: Int? = null,
        val key: List<String> = listOf("your api key"),

        val customRequestParms: YamlMap? = null
    )
    {
        val semaphore by lazy { Semaphore(maxConcurrency) }

        init
        {
            require(maxConcurrency > 0) { "maxConcurrency must be greater than 0" }
            require(key.isNotEmpty()) { "chat key must not be empty" }
        }
    }

    @Serializable
    data class ChatModel(
        val model: String,
        val displayName: String = model,
    )

    @Serializable
    data class Model(
        val url: String = "your embedding/reranker url",
        val model: String = "your embedding/reranker model",
        val maxConcurrency: Int = 50,
        val key: List<String> = listOf("your api key"),
    )
    {
        val semaphore by lazy { Semaphore(maxConcurrency) }

        init
        {
            require(maxConcurrency > 0) { "maxConcurrency must be greater than 0" }
            require(key.isNotEmpty()) { "embedding key must not be empty" }
        }
    }

    @Serializable
    data class VideoModel(
        val submitUrl: String = "https://api.siliconflow.cn/v1/video/submit",
        val statusUrl: String = "https://api.siliconflow.cn/v1/video/status",
        val i2vModel: String = "Wan-AI/Wan2.2-I2V-A14B",
        val t2vModel: String = "Wan-AI/Wan2.2-T2V-A14B",
        val sizes: List<String> = listOf("1280x720", "720x1280", "960x960"),
        val key: List<String> = listOf("your video model api key"),
    )

    init
    {
        require(timeout > 0) { "timeout must be greater than 0" }
        require(retry > 0) { "retry must be greater than 0" }
        require(answerChecker in models) { "answerChecker model not found in models" }
        require(chats.isNotEmpty()) { "chats must not be empty" }
        require(chats.all { it.model in models }) { "some chat models not found in models" }
        require(image in models) { "image model not found in models" }
        require(checker in models) { "check model not found in models" }
        require(translator in models) { "translator model not found in models" }
        require(chatNamer in models) { "chatNamer model not found in models" }
        require(contextCompressor in models) { "contextCompressor model not found in models" }
    }
}

val aiConfig: AiConfig by config("ai.yml", AiConfig())