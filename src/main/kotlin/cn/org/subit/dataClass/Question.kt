package cn.org.subit.dataClass

import cn.org.subit.plugin.contentNegotiation.QuestionAnswerSerializer
import cn.org.subit.utils.ai.AiRequest
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.reflect.KClass

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed interface Question<out Answer, out UserAnswer, out Analysis: String?>
{
    val description: String
    val options: List<String>?
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

    companion object
    {
        @Suppress("UNCHECKED_CAST")
        private fun<A, S: Any> getKSerializer(a: KSerializer<A>, b: KSerializer<S>): KSerializer<A> =
            (if (a.descriptor.isNullable) b.nullable else b) as KSerializer<A>

        @Suppress("unused", "UNCHECKED_CAST")
        @OptIn(InternalSerializationApi::class)
        fun <A: Any?, UA: Any?, Ana: String?>serializer(a: KSerializer<A>, ua: KSerializer<UA>, ana: KSerializer<Ana>): KSerializer<Question<A,UA,Ana>>
        {
            require(
                a == QuestionAnswerSerializer ||
                a == NothingSerializer().nullable ||
                a == QuestionAnswerSerializer.nullable
            ) { "a must be QuestionAnswerSerializer or nullable QuestionAnswerSerializer or nullable NothingSerializer" }
            require(
                ua == QuestionAnswerSerializer ||
                ua == NothingSerializer().nullable ||
                ua == QuestionAnswerSerializer.nullable
            ) { "ua must be QuestionAnswerSerializer or nullable QuestionAnswerSerializer or nullable NothingSerializer" }

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
                    FillQuestion.serializer(getKSerializer(a, String.serializer()), getKSerializer(ua, String.serializer()), ana),
                    EssayQuestion.serializer(getKSerializer(a, String.serializer()), getKSerializer(ua, String.serializer()), ana)
                )
            )
        }

        val examples = listOf(
            SingleChoiceQuestion.example,
            MultipleChoiceQuestion.example,
            JudgeQuestion.example,
            FillQuestion.example,
            EssayQuestion.example
        )
    }
}

@Serializable
@SerialName("single")
data class SingleChoiceQuestion<out Answer: Int?, out UserAnswer: Int?, out Analysis: String?>(
    override val description: String,
    override val options: List<String>,
    override val answer: Answer,
    override val userAnswer: UserAnswer,
    override val analysis: Analysis
): Question<Answer, UserAnswer, Analysis>
{
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
            description = "the question description",
            options = listOf("option 1", "option 2", "option 3", "option 4"),
            answer = 0,
            userAnswer = 1,
            analysis = "the analysis"
        )
    }
}

@Serializable
@SerialName("multiple")
data class MultipleChoiceQuestion<out Answer: List<Int>?, out UserAnswer: List<Int>?, out Analysis: String?>(
    override val description: String,
    override val options: List<String>,
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
            description = "the question description",
            options = listOf("option 1", "option 2", "option 3", "option 4"),
            answer = listOf(0, 1),
            userAnswer = listOf(1, 2),
            analysis = "the analysis"
        )
    }
}

@Serializable
@SerialName("judge")
data class JudgeQuestion<out Answer: Boolean?, out UserAnswer: Boolean?, out Analysis: String?>(
    override val description: String,
    override val answer: Answer,
    override val userAnswer: UserAnswer,
    override val analysis: Analysis
): Question<Answer, UserAnswer, Analysis>
{
    override val options: List<String>? get() = null
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
            description = "the question description",
            answer = true,
            userAnswer = false,
            analysis = "the analysis"
        )
    }
}

@Serializable
@SerialName("fill")
data class FillQuestion<out Answer: String?, out UserAnswer: String?, out Analysis: String?>(
    override val description: String,
    override val answer: Answer,
    override val userAnswer: UserAnswer,
    override val analysis: Analysis
): Question<Answer, UserAnswer, Analysis>
{
    init {
        require(answer == null || answer.isNotBlank()) { "answer must not be blank" }
        require(userAnswer == null || userAnswer.isNotBlank()) { "userAnswer must not be blank" }
    }
    override val options: List<String>? get() = null
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
            description = "the question description",
            answer = "the answer",
            userAnswer = "the user answer",
            analysis = "the analysis"
        )
    }
}

@Serializable
@SerialName("essay")
data class EssayQuestion<out Answer: String?, out UserAnswer: String?, out Analysis: String?>(
    override val description: String,
    override val answer: Answer,
    override val userAnswer: UserAnswer,
    override val analysis: Analysis
): Question<Answer, UserAnswer, Analysis>
{
    init
    {
        require(answer == null || answer.isNotBlank()) { "answer must not be blank" }
        require(userAnswer == null || userAnswer.isNotBlank()) { "userAnswer must not be blank" }
    }
    override val options: List<String>? get() = null
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
            description = "the question description",
            answer = "the answer",
            userAnswer = "the user answer",
            analysis = "the analysis"
        )
    }
}