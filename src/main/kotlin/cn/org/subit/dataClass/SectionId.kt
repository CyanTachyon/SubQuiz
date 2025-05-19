package cn.org.subit.dataClass

import kotlinx.serialization.Serializable

@Suppress("unused")
@JvmInline
@Serializable
value class SectionId(val value: Long): Comparable<SectionId>
{
    override fun compareTo(other: SectionId): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
    companion object
    {
        fun String.toSectionId() = SectionId(toLong())
        fun String.toSectionIdOrNull() = toLongOrNull()?.let(::SectionId)
        fun Number.toSectionId() = SectionId(toLong())
    }
}