package moe.tachyon.quiz.config

import com.charleskorn.kaml.YamlComment
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AiConfig(
    @YamlComment("AI请求的超时时间，单位为毫秒，仅限非流式请求")
    val timeout: Long = 2 * 60 * 1_000,
    @YamlComment("AI服务的重试次数")
    val retry: Int = 3,
    @YamlComment("BDFZ HELPER的API地址")
    val bdfzHelper: String = "http://localhost:8000",
    val answerChecker: String = "ds-r1",
    val chats: List<ChatModel> = listOf(ChatModel("ds-r1")),
    val image: String = "qwen-vl",
    val checker: String = "ds-r1-qwen3-8b",
    val translator: String = "ds-r1-qwen3-8b",
    val models: Map<String, Model> = mapOf(
        "ds-r1" to Model(model = "deepseek-reasoner"),
        "qwen-vl" to Model(url = "https://api.siliconflow.cn/v1/chat/completions", model = "Qwen/Qwen2.5-VL-72B-Instruct", maxTokens = 4096, imageable = true),
        "ds-r1-qwen3-8b" to Model(url = "https://api.siliconflow.cn/v1/chat/completions", model = "deepseek-ai/DeepSeek-R1-0528-Qwen3-8B", maxTokens = 8192),
    ),
)
{
    val answerCheckerModel get() = models[answerChecker]!!
    val imageModel get() = models[image]!!
    val checkModel get() = models[checker]!!
    val translatorModel get() = models[translator]!!

    @Serializable
    data class Model(
        val url: String = "https://api.deepseek.com/chat/completions",
        val model: String = "deepseek-reasoner",
        val maxTokens: Int = 16384,
        val maxConcurrency: Int = 50,
        val imageable: Boolean = false,
        val toolable: Boolean = false,
        @YamlComment("思考预算，单位为token，null表示不设置")
        @SerialName("thinking_budget")
        val thinkingBudget: Int? = null,
        val key: List<String> = listOf("your api key"),
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
        val description: String = model,
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
        require(models.all { (key, value) -> key != "bdfzHelper" }) { "bdfzHelper should not be in models" }
    }
}

val aiConfig: AiConfig by config("ai.yml", AiConfig())