@file:OptIn(ExperimentalSerializationApi::class)
package moe.tachyon.quiz.dataClass

import moe.tachyon.quiz.plugin.contentNegotiation.QuestionAnswerSerializer
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass

@JsonClassDiscriminator("type")
sealed interface Question<out Answer, out UserAnswer, out Analysis: JsonElement?>
{
    val description: JsonElement
    val options: List<JsonElement>?
    val answer: Answer
    val userAnswer: UserAnswer
    val analysis: Analysis
    val type: String

    fun hideAnswer(): Question<Nothing?, UserAnswer, Nothing?>
    fun checkFinished(): Question<Answer, UserAnswer & Any, Analysis>?
    fun withoutUserAnswer(): Question<Answer, Nothing?, Analysis>

    fun<UA> mergeUserAnswer(o: Question<*, UA, *>): Question<Answer, UA, Analysis>
    {
        require(this::class == o::class) { "Classes must be equal" }
        @Suppress("UNCHECKED_CAST")
        return when (this)
        {
            is SingleChoiceQuestion<*, *, *> -> this.mergeUserAnswer(o as SingleChoiceQuestion<*, *, *>)
            is MultipleChoiceQuestion<*, *, *> -> this.mergeUserAnswer(o as MultipleChoiceQuestion<*, *, *>)
            is JudgeQuestion<*, *, *> -> this.mergeUserAnswer(o as JudgeQuestion<*, *, *>)
            is FillQuestion<*, *, *> -> this.mergeUserAnswer(o as FillQuestion<*, *, *>)
            is EssayQuestion<*, *, *> -> this.mergeUserAnswer(o as EssayQuestion<*, *, *>)
        } as Question<Answer, UA, Analysis>
    }

    /**
     * 出题时用于检查题目是否合法
     * @return null if the question is not a choice question, or if the question is valid. Else a string describing the error.
     */
    fun checkOnCreate(): String?
    {
        if (this !is SingleChoiceQuestion<*,*,*> && this !is MultipleChoiceQuestion<*,*,*>)
            return null
        if (this.options.isNullOrEmpty()) return "选择题目不能没有选项"
        val answers = (this.answer as? Int)?.let(::listOf) ?: (this.answer as? List<*>) ?: emptyList()
        val userAnswers = (this.userAnswer as? Int)?.let(::listOf) ?: (this.userAnswer as? List<*>) ?: emptyList()
        val options1 = (this.options?.indices ?: 0..<0).toList()
        if (!options1.containsAll(answers)) return "答案不在选项中"
        if (!options1.containsAll(userAnswers)) return "用户选择的选项不在选项中"
        if (answers.isEmpty()) return "答案不能为空"
        return null
    }

    companion object
    {
        @Suppress("UNCHECKED_CAST")
        private fun<A, S: Any> getKSerializer(a: KSerializer<A>, b: KSerializer<S>): KSerializer<A> =
            (if (a.descriptor.isNullable) b.nullable else b) as KSerializer<A>

        @Suppress("unused", "UNCHECKED_CAST")
        @OptIn(InternalSerializationApi::class)
        fun <A: Any?, UA: Any?, Ana: JsonElement?>serializer(a: KSerializer<A>, ua: KSerializer<UA>, ana: KSerializer<Ana>): KSerializer<Question<A,UA,Ana>>
        {
            require(
                a == QuestionAnswerSerializer ||
                a == NothingSerializer().nullable ||
                a == QuestionAnswerSerializer.nullable
            )
            {
                "a must be QuestionAnswerSerializer or " +
                "nullable QuestionAnswerSerializer or " +
                "nullable NothingSerializer. " +
                "Got ${a.descriptor.serialName}"
            }
            require(
                ua == QuestionAnswerSerializer ||
                ua == NothingSerializer().nullable ||
                ua == QuestionAnswerSerializer.nullable
            )
            {
                "ua must be QuestionAnswerSerializer or " +
                "nullable QuestionAnswerSerializer or " +
                "nullable NothingSerializer. " +
                "Got ${ua.descriptor.serialName}"
            }

            return SealedClassSerializer(
                "Question<${a.descriptor.serialName}, ${ua.descriptor.serialName}, ${ana.descriptor.serialName}>",
                Question::class as KClass<Question<A, UA, Ana>>,
                arrayOf(
                    SingleChoiceQuestion::class as KClass<Question<A, UA, Ana>>,
                    MultipleChoiceQuestion::class as KClass<Question<A, UA, Ana>>,
                    JudgeQuestion::class as KClass<Question<A, UA, Ana>>,
                    FillQuestion::class as KClass<Question<A, UA, Ana>>,
                    EssayQuestion::class as KClass<Question<A, UA, Ana>>,
                ),
                arrayOf(
                    SingleChoiceQuestion.serializer(getKSerializer(a, Int.serializer()), getKSerializer(ua, Int.serializer()), ana),
                    MultipleChoiceQuestion.serializer(getKSerializer(a, ListSerializer(Int.serializer())), getKSerializer(ua, ListSerializer(Int.serializer())), ana),
                    JudgeQuestion.serializer(getKSerializer(a, Boolean.serializer()), getKSerializer(ua, Boolean.serializer()), ana),
                    FillQuestion.serializer(getKSerializer(a, JsonElement.serializer ()), getKSerializer(ua, String.serializer()), ana),
                    EssayQuestion.serializer(getKSerializer(a, JsonElement.serializer()), getKSerializer(ua, String.serializer()), ana)
                )
            )
        }

        val examples by lazy()
        {
            listOf(
                SingleChoiceQuestion.example,
                MultipleChoiceQuestion.example,
                JudgeQuestion.example,
                FillQuestion.example,
                EssayQuestion.example
            )
        }
    }
}

@Serializable
@SerialName("single")
data class SingleChoiceQuestion<out Answer: Int?, out UserAnswer: Int?, out Analysis: JsonElement?>(
    override val description: JsonElement,
    override val options: List<JsonElement>?,
    override val answer: Answer,
    override val userAnswer: UserAnswer,
    override val analysis: Analysis
): Question<Answer, UserAnswer, Analysis>
{
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type = "single"
    override fun hideAnswer(): SingleChoiceQuestion<Nothing?, UserAnswer, Nothing?> =
        SingleChoiceQuestion(description, options, null, userAnswer, null)
    @Suppress("UNCHECKED_CAST")
    override fun checkFinished(): SingleChoiceQuestion<Answer, UserAnswer & Any, Analysis>? =
        if (userAnswer == null) null else this as SingleChoiceQuestion<Answer, UserAnswer & Any, Analysis>
    override fun withoutUserAnswer(): SingleChoiceQuestion<Answer, Nothing?, Analysis> =
        SingleChoiceQuestion(description, options, answer, null, analysis)

    fun <UA: Int?>mergeUserAnswer(o: SingleChoiceQuestion<*, UA, *>): SingleChoiceQuestion<Answer, UA, Analysis> =
        SingleChoiceQuestion(description, options, answer, o.userAnswer, analysis)

    companion object
    {
        val example = SingleChoiceQuestion(
            description = JsonObject(emptyMap()),
            options = listOf(
                JsonObject(emptyMap()),
                JsonObject(emptyMap()),
                JsonObject(emptyMap()),
                JsonObject(emptyMap()),
            ),
            answer = 0,
            userAnswer = 1,
            analysis = JsonObject(emptyMap()),
        )
    }
}

@Serializable
@SerialName("multiple")
data class MultipleChoiceQuestion<out Answer: List<Int>?, out UserAnswer: List<Int>?, out Analysis: JsonElement?>(
    override val description: JsonElement,
    override val options: List<JsonElement>?,
    override val answer: Answer,
    override val userAnswer: UserAnswer,
    override val analysis: Analysis
): Question<Answer, UserAnswer, Analysis>
{
    init
    {
        require(answer == null || answer.isNotEmpty()) { "answer must not be empty" }
        require(userAnswer == null || userAnswer.isNotEmpty()) { "userAnswer must not be empty" }
    }
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type = "multiple"
    override fun hideAnswer(): MultipleChoiceQuestion<Nothing?, UserAnswer, Nothing?> =
        MultipleChoiceQuestion(description, options, null, userAnswer, null)
    @Suppress("UNCHECKED_CAST")
    override fun checkFinished(): MultipleChoiceQuestion<Answer, UserAnswer & Any, Analysis>? =
        if (userAnswer == null) null else this as MultipleChoiceQuestion<Answer, UserAnswer & Any, Analysis>
    override fun withoutUserAnswer(): MultipleChoiceQuestion<Answer, Nothing?, Analysis> =
        MultipleChoiceQuestion(description, options, answer, null, analysis)

    fun <UA: List<Int>?>mergeUserAnswer(o: MultipleChoiceQuestion<*, UA, *>): MultipleChoiceQuestion<Answer, UA, Analysis> =
        MultipleChoiceQuestion(description, options, answer, o.userAnswer, analysis)

    companion object
    {
        val example = MultipleChoiceQuestion(
            description = JsonObject(emptyMap()),
            options = listOf(
                JsonObject(emptyMap()),
                JsonObject(emptyMap()),
                JsonObject(emptyMap()),
                JsonObject(emptyMap()),
            ),
            answer = listOf(0, 1),
            userAnswer = listOf(1, 2),
            analysis = JsonObject(emptyMap()),
        )
    }
}

@Serializable
@SerialName("judge")
data class JudgeQuestion<out Answer: Boolean?, out UserAnswer: Boolean?, out Analysis: JsonElement?>(
    override val description: JsonElement,
    override val answer: Answer,
    override val userAnswer: UserAnswer,
    override val analysis: Analysis
): Question<Answer, UserAnswer, Analysis>
{
    override val options: List<JsonElement>? get() = null
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type = "judge"
    override fun hideAnswer(): JudgeQuestion<Nothing?, UserAnswer, Nothing?> =
        JudgeQuestion(description, null, userAnswer, null)
    @Suppress("UNCHECKED_CAST")
    override fun checkFinished(): JudgeQuestion<Answer, UserAnswer & Any, Analysis>? =
        if (userAnswer == null) null else this as JudgeQuestion<Answer, UserAnswer & Any, Analysis>
    override fun withoutUserAnswer(): JudgeQuestion<Answer, Nothing?, Analysis> =
        JudgeQuestion(description, answer, null, analysis)

    fun <UA: Boolean?>mergeUserAnswer(o: JudgeQuestion<*, UA, *>): JudgeQuestion<Answer, UA, Analysis> =
        JudgeQuestion(description, answer, o.userAnswer, analysis)

    companion object
    {
        val example = JudgeQuestion(
            description = JsonObject(emptyMap()),
            answer = true,
            userAnswer = false,
            analysis = JsonObject(emptyMap()),
        )
    }
}

@Serializable
@SerialName("fill")
data class FillQuestion<out Answer: JsonElement?, out UserAnswer: String?, out Analysis: JsonElement?>(
    override val description: JsonElement,
    override val answer: Answer,
    override val userAnswer: UserAnswer,
    override val analysis: Analysis
): Question<Answer, UserAnswer, Analysis>
{
    init {
        require(userAnswer == null || userAnswer.isNotBlank()) { "userAnswer must not be blank" }
    }
    override val options: List<JsonElement>? get() = null
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type = "fill"
    override fun hideAnswer(): FillQuestion<Nothing?, UserAnswer, Nothing?> =
        FillQuestion(description, null, userAnswer, null)
    @Suppress("UNCHECKED_CAST")
    override fun checkFinished(): FillQuestion<Answer, UserAnswer & Any, Analysis>? =
        if (userAnswer == null) null else this as FillQuestion<Answer, UserAnswer & Any, Analysis>
    override fun withoutUserAnswer(): FillQuestion<Answer, Nothing?, Analysis> =
        FillQuestion(description, answer, null, analysis)

    fun <UA: String?>mergeUserAnswer(o: FillQuestion<*, UA, *>): FillQuestion<Answer, UA, Analysis> =
        FillQuestion(description, answer, o.userAnswer, analysis)

    companion object
    {
        val example = FillQuestion(
            description = JsonObject(emptyMap()),
            answer = JsonObject(emptyMap()),
            userAnswer = "the user answer",
            analysis = JsonObject(emptyMap()),
        )
    }
}

@Serializable
@SerialName("essay")
data class EssayQuestion<out Answer: JsonElement?, out UserAnswer: String?, out Analysis: JsonElement?>(
    override val description: JsonElement,
    override val answer: Answer,
    override val userAnswer: UserAnswer,
    override val analysis: Analysis
): Question<Answer, UserAnswer, Analysis>
{
    init
    {
        require(userAnswer == null || userAnswer.isNotBlank()) { "userAnswer must not be blank" }
    }
    override val options: List<JsonElement>? get() = null
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type = "essay"
    override fun hideAnswer(): EssayQuestion<Nothing?, UserAnswer, Nothing?> =
        EssayQuestion(description, null, userAnswer, null)
    @Suppress("UNCHECKED_CAST")
    override fun checkFinished(): EssayQuestion<Answer, UserAnswer & Any, Analysis>? =
        if (userAnswer == null) null else this as EssayQuestion<Answer, UserAnswer & Any, Analysis>
    override fun withoutUserAnswer(): EssayQuestion<Answer, Nothing?, Analysis> =
        EssayQuestion(description, answer, null, analysis)

    fun <UA: String?>mergeUserAnswer(o: EssayQuestion<*, UA, *>): EssayQuestion<Answer, UA, Analysis> =
        EssayQuestion(description, answer, o.userAnswer, analysis)

    companion object
    {
        val example = EssayQuestion(
            description = JsonObject(emptyMap()),
            answer = JsonObject(emptyMap()),
            userAnswer = "the user answer",
            analysis = JsonObject(emptyMap()),
        )
    }
}