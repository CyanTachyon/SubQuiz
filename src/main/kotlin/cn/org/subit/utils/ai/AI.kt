package cn.org.subit.utils.ai

import cn.org.subit.config.aiConfig
import cn.org.subit.dataClass.*
import cn.org.subit.database.KnowledgePoints
import cn.org.subit.database.PreparationGroups
import cn.org.subit.database.SectionTypes
import cn.org.subit.database.Subjects
import cn.org.subit.logger.SubQuizLogger
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.getValue

@Suppress("unused")
object AI: KoinComponent
{
    private val logger = SubQuizLogger.getLogger<AI>()
    private val subjects by inject<Subjects>()
    private val sectionTypes by inject<SectionTypes>()
    private val knowledgePoints by inject<KnowledgePoints>()
    private val preparationGroups by inject<PreparationGroups>()
    @OptIn(DelicateCoroutinesApi::class)
    private val checkAnswerCoroutineScope = CoroutineScope(newFixedThreadPoolContext(aiConfig.maxConcurrency, "checkAnswerDispatcher"))

    fun Section<Any, Any, *>.checkAnswerAsync(): Deferred<Pair<List<Boolean?>, AiResponse.Usage>> =
        checkAnswerCoroutineScope.async { checkAnswer() }
    suspend fun Section<Any, Any, *>.checkAnswer(): Pair<List<Boolean?>, AiResponse.Usage>
    {
        val subject =
            sectionTypes.getSectionType(type)?.knowledgePoint
                ?.let { knowledgePoints.getKnowledgePoint(it) }?.group
                ?.let { preparationGroups.getPreparationGroup(it) }?.subject
                ?.let { subjects.getSubject(it) }
        var totalTokens = AiResponse.Usage()
        val res = this.questions.mapIndexed { index, it ->
            checkAnswerCoroutineScope.async {
                when (it)
                {
                    is FillQuestion, is EssayQuestion -> runCatching {
                        checkAnswer(
                            subject?.name,
                            this@checkAnswer.description,
                            it.description,
                            it.userAnswer.toString(),
                            it.answer.toString()
                        ).let { ans -> totalTokens += ans.second; ans.first }
                    }.onFailure { e ->
                        logger.warning("检查答案失败, SectionID: $id, QuestionIndex: $index", e)
                        if (e is AiRetryFailedException) e.exceptions.forEachIndexed()
                        { i, cause ->
                            logger.warning("Cause$i: ", cause)
                        }
                    }.getOrNull()
                    is JudgeQuestion, is SingleChoiceQuestion -> it.userAnswer == it.answer
                    is MultipleChoiceQuestion ->
                    {
                        val x = it as MultipleChoiceQuestion<*, *, *>
                        x.userAnswer?.toSet() == x.answer?.toSet()
                    }
                }
            }
        }.map { it.await() }

        return res to totalTokens
    }

    private val answerRegex = Regex(".*\"result\" *: *(true|false).*")
    suspend fun checkAnswer(
        subjectName: String?,
        sectionDescription: String,
        questionDescription: String,
        userAnswer: String,
        standard: String,
    ): Pair<Boolean, AiResponse.Usage>
    {
        val prompt = makePrompt(subjectName, sectionDescription, questionDescription, userAnswer, standard)
        val messages = listOf(AiRequest.Message(Role.USER, listOf(AiRequest.Message.Content(prompt))))
        var totalTokens = AiResponse.Usage()
        val errors = mutableListOf<Throwable>()
        repeat(aiConfig.retry)
        {
            try
            {
                val res = sendAiRequest(
                    url = aiConfig.chat.url,
                    key = aiConfig.chat.key.random(),
                    messages = messages,
                    model = aiConfig.chat.model,
                    maxTokens = aiConfig.chat.maxTokens,
                    responseFormat = if (aiConfig.chat.useJsonOutput) AiRequest.ResponseFormat(AiRequest.ResponseFormat.Type.JSON) else null,
                )
                totalTokens += res.usage
                val content = res.choices[0].message.content
                val matchResult = answerRegex.findAll(content).toList()
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

    private val codeBlockRegex = Regex("```.*\\n?")

    private fun makePrompt(
        subjectName: String?,
        sectionDescription: String,
        questionDescription: String,
        userAnswer: String,
        standard: String,
    ): String
    {
        val sb = StringBuilder()
        sb.append("请你帮忙进行判卷，")
        sb.append("根据题目信息和评分标准，检查学生的答案是否正确\n")

        if (!subjectName.isNullOrBlank())
        {
            sb.append("题目所属科目: $subjectName\n\n")
        }

        sb.append("题目信息: \n")
        sb.append("```\n")
        sb.append(sectionDescription.replace(codeBlockRegex, ""))
        sb.append("\n")
        sb.append(questionDescription.replace(codeBlockRegex, ""))
        sb.append("\n```\n\n")

        sb.append("标准答案/评分标准: \n")
        sb.append("```\n")
        sb.append(standard.replace(codeBlockRegex, ""))
        sb.append("\n```\n\n")

        sb.append("学生答案: \n")
        sb.append("```\n")
        sb.append(userAnswer.replace(codeBlockRegex, ""))
        sb.append("\n```\n\n")

        sb.append("请你根据题目信息和评分标准，检查学生的答案是否正确，并返回且仅返回一个json对象，")
        sb.append("其中包含一个\"result\"字段，其类型为bool，表示给定的答案是否正确\n")
        return sb.toString()
    }
}