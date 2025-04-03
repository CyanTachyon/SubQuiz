package cn.org.subit.utils.ai

import cn.org.subit.plugin.contentNegotiation.showJson

class AiRetryFailedException(
    val exceptions: List<Throwable>
): Exception("尝试次数达到上限仍未获取有效的答复: ${exceptions.joinToString("\n") { it.message.orEmpty() }}")

class AiResponseException(
    val response: AiResponse
): Exception("AI的响应无效: ${showJson.encodeToString(response)}")