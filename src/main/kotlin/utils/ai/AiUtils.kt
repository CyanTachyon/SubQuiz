package moe.tachyon.quiz.utils.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.utils.ai.internal.llm.sendAiRequest
import kotlin.reflect.KType
import kotlin.reflect.typeOf

val aiNegotiationJson = Json(contentNegotiationJson)
{
    ignoreUnknownKeys = false
    isLenient = true
    prettyPrint = true
}

private val logger = SubQuizLogger.getLogger()

interface ResultType<T>
{
    fun getValue(str: String): T

    companion object
    {
        val STRING = WrapResultType<String>()
        val BOOLEAN = WrapResultType<Boolean>()
        val INTEGER = WrapResultType<Long>()
        val FLOAT = WrapResultType<Double>()
    }

    class ResultTypeImpl<T: Any>(private val type: KType): ResultType<T>
    {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(str: String): T =
            aiNegotiationJson.decodeFromString(aiNegotiationJson.serializersModule.serializer(type), str) as T
    }

    private class WrapResultType<T: Any>(private val type: KType): ResultType<T>
    {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(str: String): T =
            aiNegotiationJson.decodeFromString(Result.serializer(aiNegotiationJson.serializersModule.serializer(type)), str).result as T

        companion object
        {
            inline operator fun <reified T: Any> invoke(): ResultType<T> =
                WrapResultType(type = typeOf<T>())
        }
    }
}

inline fun <reified T: Any> ResultType(): ResultType<T>
{
    @Suppress("UNCHECKED_CAST")
    return when (T::class)
    {
        String::class               -> ResultType.STRING as ResultType<T>
        Boolean::class              -> ResultType.BOOLEAN as ResultType<T>
        Long::class, Int::class     -> ResultType.INTEGER as ResultType<T>
        Double::class, Float::class -> ResultType.FLOAT as ResultType<T>
        else                        -> ResultType.ResultTypeImpl(typeOf<T>())
    }
}

@Serializable private data class Result<T>(val result: T)

/**
 * 重试方式，该方式仅决定当AI返回了内容，但AI的返回内容不符合ResultType时的处理方式
 *
 * 若AI请求失败（网络异常或AI服务返回错误），则直接重试发送请求，和该选项无关
 */
enum class RetryType
{
    /**
     * 重新发送请求
     */
    RESEND,
    /**
     * 添加一条消息，提示AI修正
     */
    ADD_MESSAGE,
}

suspend inline fun <reified T: Any> sendAiRequestAndGetResult(
    model: AiConfig.LlmModel,
    message: String,
    resultType: ResultType<T> = ResultType<T>(),
    retryType: RetryType = RetryType.RESEND,
    record: Boolean = true,
): Pair<T, TokenUsage> = sendAiRequestAndGetResult(
    model = model,
    messages = ChatMessages(Role.USER, message),
    resultType = resultType,
    retryType = retryType,
    record = record,
    impl = Unit,
)

suspend inline fun <reified T: Any> sendAiRequestAndGetResult(
    model: AiConfig.LlmModel,
    messages: ChatMessages,
    resultType: ResultType<T> = ResultType<T>(),
    retryType: RetryType = RetryType.RESEND,
    record: Boolean = true,
): Pair<T, TokenUsage> = sendAiRequestAndGetResult(
    model = model,
    messages = messages,
    resultType = resultType,
    retryType = retryType,
    record = record,
    impl = Unit,
)

suspend fun <T: Any> sendAiRequestAndGetResult(
    model: AiConfig.LlmModel,
    messages: ChatMessages,
    resultType: ResultType<T>,
    retryType: RetryType,
    record: Boolean,
    @Suppress("unused") impl: Unit
): Pair<T, TokenUsage>
{
    var totalTokens = TokenUsage()
    val errors = mutableListOf<AiResponseException>()
    var messages = messages
    repeat(aiConfig.retry)
    {
        val res: Pair<ChatMessage, TokenUsage>
        try
        {
            res = sendAiRequest(
                model = model,
                messages = messages,
                record = record,
            )
            totalTokens += res.second
        }
        catch (e: Throwable)
        {
            currentCoroutineContext().ensureActive()
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
            return resultType.getValue(content) to totalTokens
        }
        catch (e: Throwable)
        {
            currentCoroutineContext().ensureActive()
            val error = AiResponseFormatException(res.first, cause = e)
            errors.add(error)
            logger.config("检查AI响应格式失败", error)
            when (retryType)
            {
                RetryType.RESEND      -> Unit
                RetryType.ADD_MESSAGE ->
                {
                    messages += ChatMessages(
                        Role.USER,
                        "你返回的内容格式不符合规定，请严格按照要求的JSON格式返回: ${e.message}\n\n" +
                        "请你重新输出结果，注意仅输出JSON对象，不要添加任何多余的文本。并修正错误。"
                    )
                }
            }
        }
    }
    currentCoroutineContext().ensureActive()
    throw AiRetryFailedException(errors)
}