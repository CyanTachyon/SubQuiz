package moe.tachyon.quiz.utils.ai

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import moe.tachyon.quiz.utils.ai.internal.sendAiRequest

private val logger = SubQuizLogger.getLogger()

interface ResultType<T>
{
    fun getValue(str: String): T
    object STRING: ResultType<String>
    {
        override fun getValue(str: String) =
            showJson.decodeFromString<Result<String>>(str).result
    }

    object BOOLEAN: ResultType<Boolean>
    {
        override fun getValue(str: String) =
            showJson.decodeFromString<Result<Boolean>>(str).result
    }

    object INTEGER: ResultType<Long>
    {
        override fun getValue(str: String) =
            showJson.decodeFromString<Result<Long>>(str).result
    }

    object FLOAT: ResultType<Double>
    {
        override fun getValue(str: String) =
            showJson.decodeFromString<Result<Double>>(str).result
    }
}

@Serializable private data class Result<T>(val result: T)

suspend fun <T> sendAiRequestAndGetResult(
    model: AiConfig.Model,
    message: String,
    resultType: ResultType<T>,
): Pair<T, TokenUsage> = sendAiRequestAndGetResult(
    model = model,
    messages = ChatMessages(Role.SYSTEM, message),
    resultType = resultType,
)

suspend fun <T> sendAiRequestAndGetResult(
    model: AiConfig.Model,
    messages: ChatMessages,
    resultType: ResultType<T>,
): Pair<T, TokenUsage>
{
    var totalTokens = TokenUsage()
    val errors = mutableListOf<AiResponseException>()
    repeat(aiConfig.retry)
    {
        val res: Pair<ChatMessage, TokenUsage>
        try
        {
            res = sendAiRequest(
                model = model,
                messages = messages,
            )
            totalTokens += res.second
        }
        catch (e: Throwable)
        {
            errors.add(UnknownAiResponseException(e))
            logger.config("发送AI请求失败", e)
            return@repeat
        }
        try
        {
            val content = res.first.content.toText().trim()
            if (content.startsWith("```") && content.endsWith("```"))
            {
                val jsonContent = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                return resultType.getValue(jsonContent) to totalTokens
            }
            else if (content.startsWith("{") && content.endsWith("}"))
            {
                return resultType.getValue(content) to totalTokens
            }
            val error = AiResponseFormatException(res.first)
            errors.add(error)
            logger.config("AI的响应无效", error)
        }
        catch (e: Throwable)
        {
            val error = AiResponseFormatException(res.first, e)
            errors.add(error)
            logger.config("检查AI响应格式失败", error)
        }
    }
    throw AiRetryFailedException(errors)
}