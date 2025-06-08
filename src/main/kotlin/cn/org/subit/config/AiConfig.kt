package cn.org.subit.config

import kotlinx.serialization.Serializable
import net.mamoe.yamlkt.Comment

@Serializable
data class AiConfig(
    val timeout: Long = 2 * 60 * 1_000,
    val retry: Int = 3,
    @Comment("最大并发数, 受技术限制，修改此项必须重启整个服务，仅使用reload命令不会生效")
    val maxConcurrency: Int = 10,
    val bdfzHelper: String = "http://localhost:8000",
    val chat: ChatConfig = ChatConfig(),
    val image: ImageConfig = ImageConfig(),
)
{
    @Serializable
    data class ChatConfig(
        val url: String = "https://api.deepseek.com/chat/completions",
        val key: List<String> = listOf("your api key"),
        val model: String = "deepseek-reasoner",
        val useJsonOutput: Boolean = false,
        val maxTokens: Int = 16384,
    )

    @Serializable
    data class ImageConfig(
        val url: String = "https://api.siliconflow.cn/v1/chat/completions",
        val key: List<String> = listOf("your api key"),
        val model: String = "Qwen/Qwen2.5-72B-Instruct",
        val maxTokens: Int = 16384,
    )

    init
    {
        require(timeout > 0) { "timeout must be greater than 0" }
        require(retry > 0) { "retry must be greater than 0" }
        require(maxConcurrency > 0) { "maxConcurrency must be greater than 0" }
        require(chat.key.isNotEmpty()) { "chat key must not be empty" }
        require(image.key.isNotEmpty()) { "image key must not be empty" }
    }
}

var aiConfig: AiConfig by config("ai.yml", AiConfig())