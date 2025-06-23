package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable

@Suppress("unused")
@JvmInline
@Serializable
value class SubjectId(val value: Int): Comparable<SubjectId>
{
    override fun compareTo(other: SubjectId): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
    companion object
    {
        fun String.toSubjectId() = SubjectId(toInt())
        fun String.toSubjectIdOrNull() = toIntOrNull()?.let(::SubjectId)
        fun Number.toSubjectId() = SubjectId(toInt())
    }
}