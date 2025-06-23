package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable

@Suppress("unused")
@JvmInline
@Serializable
value class PreparationGroupId(val value: Int): Comparable<PreparationGroupId>
{
    override fun compareTo(other: PreparationGroupId): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
    companion object
    {
        fun String.toPreparationGroupId() = PreparationGroupId(toInt())
        fun String.toPreparationGroupIdOrNull() = toIntOrNull()?.let(::PreparationGroupId)
        fun Number.toPreparationGroupId() = PreparationGroupId(toInt())
    }
}

@Serializable
data class PreparationGroup(
    val id: PreparationGroupId = PreparationGroupId(0),
    val subject: SubjectId,
    val name: String,
    val description: String,
    val time: Long = System.currentTimeMillis(),
)
{
    companion object
    {
        val example = PreparationGroup(
            id = PreparationGroupId(1),
            subject = SubjectId(1),
            name = "a preparation group",
            description = "the description",
            time = System.currentTimeMillis(),
        )
    }
}