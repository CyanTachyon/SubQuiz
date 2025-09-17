package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable

@Suppress("unused")
@JvmInline
@Serializable
value class PracticeId(val value: Int): Comparable<PracticeId>
{
    override fun compareTo(other: PracticeId) = value.compareTo(other.value)
    override fun toString(): String = value.toString()
    companion object
    {
        fun String.toPracticeId() = PracticeId(toInt())
        fun String.toPracticeIdOrNull() = toIntOrNull()?.let(::PracticeId)
        fun Number.toPracticeId() = PracticeId(toInt())
    }
}