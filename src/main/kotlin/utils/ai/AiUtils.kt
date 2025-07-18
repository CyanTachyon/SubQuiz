package moe.tachyon.quiz.utils.ai

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.showJson

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
): Pair<T, AiResponse.Usage> = sendAiRequestAndGetResult(
    model = model,
    messages = listOf(AiRequest.Message(Role.SYSTEM, message)),
    resultType = resultType,
)

suspend fun <T> sendAiRequestAndGetResult(
    model: AiConfig.Model,
    messages: List<AiRequest.Message>,
    resultType: ResultType<T>,
): Pair<T, AiResponse.Usage>
{
    var totalTokens = AiResponse.Usage()
    val errors = mutableListOf<Throwable>()
    repeat(aiConfig.retry)
    {
        try
        {
            val res = sendAiRequest(
                model = model,
                messages = messages,
            )
            totalTokens += res.usage
            val content = res.choices.joinToString(separator = "") { it.message.content }.trim()
            if (content.startsWith("```") && content.endsWith("```"))
            {
                val jsonContent = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                return resultType.getValue(jsonContent) to totalTokens
            }
            else if (content.startsWith("{") && content.endsWith("}"))
            {
                return resultType.getValue(content) to totalTokens
            }
            val error = AiResponseException(res)
            errors.add(error)
            logger.config("AI的响应无效", error)
        }
        catch (e: Throwable)
        {
            errors.add(e)
            logger.config("检查发送AI请求失败", e)
        }
    }
    throw AiRetryFailedException(errors)
}