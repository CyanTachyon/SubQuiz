package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable

@Suppress("unused")
@JvmInline
@Serializable
value class KnowledgePointId(val value: Long): Comparable<KnowledgePointId>
{
    override fun compareTo(other: KnowledgePointId): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
    companion object
    {
        fun String.toKnowledgePointId() = KnowledgePointId(toLong())
        fun String.toKnowledgePointIdOrNull() = toLongOrNull()?.let(::KnowledgePointId)
        fun Number.toKnowledgePointId() = KnowledgePointId(toLong())
    }
}

@Serializable
data class KnowledgePoint(
    val id: KnowledgePointId = KnowledgePointId(0),
    val group: PreparationGroupId,
    val name: String,
    val folder: Boolean,
    val father: KnowledgePointId?,
)
{
    companion object
    {
        val example = KnowledgePoint(
            id = KnowledgePointId(1),
            group = PreparationGroupId(1),
            name = "a knowledge point",
            folder = false,
            father = null,
        )
    }
}