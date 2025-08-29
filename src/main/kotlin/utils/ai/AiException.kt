package moe.tachyon.quiz.utils.ai

import moe.tachyon.quiz.plugin.contentNegotiation.showJson

class AiRetryFailedException(
    val exceptions: List<AiResponseException>
): Exception("尝试次数达到上限仍未获取有效的答复: \n${exceptions.joinToString("\n") { it.message.orEmpty() }}")

sealed class AiResponseException(msg: String, cause: Throwable? = null): Exception(msg, cause)
class AiResponseFormatException(
    val response: ChatMessages,
    val msg: String = "AI的响应格式无效",
    cause: Throwable? = null
): AiResponseException(
    "$msg: ${showJson.encodeToString(response)}",
    cause
)
{
    constructor(response: ChatMessage, msg: String = "AI的响应格式无效", cause: Throwable? = null): this(ChatMessages(response), msg, cause)
}
class UnknownAiResponseException(cause: Throwable? = null): AiResponseException("未知的AI响应: $cause", cause)