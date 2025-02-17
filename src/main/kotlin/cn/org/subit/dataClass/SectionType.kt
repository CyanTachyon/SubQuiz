package cn.org.subit.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class SectionType(
    val id: SectionTypeId,
    val subject: SubjectId,
    val name: String,
    val description: String,
)
{
    companion object
    {
        val example = SectionType(SectionTypeId(1), SubjectId(1), "a subject type", "the description")
    }
}