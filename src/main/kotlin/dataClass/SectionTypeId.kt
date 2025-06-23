package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable

@Suppress("unused")
@JvmInline
@Serializable
value class SectionTypeId(val value: Int): Comparable<SectionTypeId>
{
    override fun compareTo(other: SectionTypeId): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
    companion object
    {
        fun String.toSectionTypeId() = SectionTypeId(toInt())
        fun String.toSectionTypeIdOrNull() = toIntOrNull()?.let(::SectionTypeId)
        fun Number.toSectionTypeId() = SectionTypeId(toInt())
    }
}