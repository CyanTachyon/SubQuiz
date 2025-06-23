package moe.tachyon.quiz.utils.ai

import moe.tachyon.quiz.plugin.contentNegotiation.showJson

class AiRetryFailedException(
    val exceptions: List<Throwable>
): Exception("尝试次数达到上限仍未获取有效的答复: ${exceptions.joinToString("\n") { it.message.orEmpty() }}")

class AiResponseException(
    val response: DefaultAiResponse
): Exception("AI的响应无效: ${showJson.encodeToString(response)}")