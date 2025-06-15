package cn.org.subit.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.mamoe.yamlkt.Comment
import kotlin.compareTo
import kotlin.getValue
import kotlin.math.max

@Serializable
data class AiConfig(
    @Comment("AI请求的超时时间，单位为毫秒，仅限非流式请求")
    val timeout: Long = 2 * 60 * 1_000,
    @Comment("AI服务的重试次数")
    val retry: Int = 3,
    @Comment("BDFZ HELPER的API地址")
    val bdfzHelper: String = "http://localhost:8000",
    val answerChecker: Model = Model(),
    val chat: Model = Model(model = "deepseek-reasoner"),
    val image: Model = Model(url = "https://api.siliconflow.cn/v1/chat/completions", model = "Qwen/Qwen2.5-VL-72B-Instruct", maxTokens = 4096),
    val check: Model = Model(url = "https://api.siliconflow.cn/v1/chat/completions", model = "deepseek-ai/DeepSeek-R1-0528-Qwen3-8B", maxTokens = 8192),
)
{
    @Serializable
    data class Model(
        val url: String = "https://api.deepseek.com/chat/completions",
        val key: List<String> = listOf("your api key"),
        val model: String = "deepseek-reasoner",
        val maxTokens: Int = 16384,
        val maxConcurrency: Int = 10,
    )
    {
        val semaphore by lazy { Semaphore(maxConcurrency) }

        init
        {
            require(maxConcurrency > 0) { "maxConcurrency must be greater than 0" }
            require(key.isNotEmpty()) { "chat key must not be empty" }
        }
    }

    init
    {
        require(timeout > 0) { "timeout must be greater than 0" }
        require(retry > 0) { "retry must be greater than 0" }
    }
}

var aiConfig: AiConfig by config("ai.yml", AiConfig())