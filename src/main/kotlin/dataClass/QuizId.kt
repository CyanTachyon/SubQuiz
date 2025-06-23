package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable

@Suppress("unused")
@JvmInline
@Serializable
value class QuizId(val value: Long): Comparable<QuizId>
{
    override fun compareTo(other: QuizId) = value.compareTo(other.value)
    override fun toString(): String = value.toString()
    companion object
    {
        fun String.toQuizId() = QuizId(toLong())
        fun String.toQuizIdOrNull() = toLongOrNull()?.let(::QuizId)
        fun Number.toQuizId() = QuizId(toLong())
    }
}