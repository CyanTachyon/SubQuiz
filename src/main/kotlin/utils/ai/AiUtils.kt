package moe.tachyon.quiz.utils.ai

import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.logger.SubQuizLogger

private val logger = SubQuizLogger.getLogger()
private val judgeRegex = Regex(".*\"result\" *: *(true|false).*")

suspend fun sendJudgeRequest(
    model: AiConfig.Model,
    message: String,
): Pair<Boolean, AiResponse.Usage> = sendJudgeRequest(
    model = model,
    messages = listOf(AiRequest.Message(Role.SYSTEM, message))
)

suspend fun sendJudgeRequest(
    model: AiConfig.Model,
    messages: List<AiRequest.Message>,
): Pair<Boolean, AiResponse.Usage>
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
            val content = res.choices[0].message.content
            val matchResult = judgeRegex.findAll(content).toList()
            if (matchResult.size == 1)
            {
                val result = matchResult[0].groupValues[1]
                return result.toBoolean() to totalTokens
            }
            val error = AiResponseException(res)
            errors.add(error)
            logger.config("AI的响应无效", error)
        }
        catch (e: Throwable)
        {
            errors.add(e)
            logger.config("检查答案失败", e)
        }
    }
    throw AiRetryFailedException(errors)
}