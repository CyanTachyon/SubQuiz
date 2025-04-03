package cn.org.subit.config

import kotlinx.serialization.Serializable
import net.mamoe.yamlkt.Comment

@Serializable
data class AiConfig(
    val timeout: Long = 2 * 60 * 1_000,
    val retry: Int = 3,
    @Comment("最大并发数, 受技术限制，修改此项必须重启整个服务，仅使用reload命令不会生效")
    val maxConcurrency: Int = 10,

    val chat: ChatConfig = ChatConfig(),
    val image: ImageConfig = ImageConfig(),
)
{
    @Serializable
    data class ChatConfig(
        val url: String = "https://api.deepseek.com/chat/completions",
        val key: String = "your api key",
        val model: String = "deepseek-reasoner",
        val useJsonOutput: Boolean = false,
        val maxTokens: Int = 16384,
    )

    @Serializable
    data class ImageConfig(
        val url: String = "https://api.siliconflow.cn/v1/chat/completions",
        val key: String = "your api key",
        val model: String = "deepseek-ai/deepseek-vl2",
        val maxTokens: Int = 16384,
    )
}

var aiConfig: AiConfig by config("ai.yml", AiConfig())