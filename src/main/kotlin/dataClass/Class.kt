package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.database.ClassMembers
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Suppress("unused")
@JvmInline
@Serializable
value class ClassId(val value: Int): Comparable<ClassId>
{
    override fun compareTo(other: ClassId): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
    companion object
    {
        fun String.toClassId() = ClassId(toInt())
        fun String.toClassIdOrNull() = toIntOrNull()?.let(::ClassId)
        fun Number.toClassId() = ClassId(toInt())
    }
}

@Serializable
data class Class(
    val id: ClassId = ClassId(0),
    val name: String,
    val group: PreparationGroupId,
)
{
    suspend fun withMembers() = ClassWithMembers(
        id = id,
        name = name,
        group = group,
        members = classMembers.getClassMembers(id),
    )

    companion object: KoinComponent
    {
        private val classMembers: ClassMembers by inject()
        val example = Class(
            id = ClassId(1),
            name = "a class",
            group = PreparationGroupId(1),
        )
    }
}

@Serializable
data class ClassMember(
    val user: UserId?,
    val seiue: SsoUserFull.Seiue,
)
{
    companion object
    {
        val example = ClassMember(
            user = UserId(1),
            seiue = SsoUserFull.Seiue("example", "Example User", false),
        )
    }
}

@Serializable
data class ClassWithMembers(
    val id: ClassId,
    val name: String,
    val group: PreparationGroupId,
    val members: List<ClassMember>,
)
{
    companion object
    {
        val example = ClassWithMembers(
            id = ClassId(1),
            name = "a class",
            group = PreparationGroupId(1),
            members = listOf(ClassMember.example)
        )
    }
}