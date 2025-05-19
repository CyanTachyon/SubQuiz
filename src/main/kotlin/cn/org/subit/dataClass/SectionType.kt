package cn.org.subit.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class SectionType(
    val id: SectionTypeId = SectionTypeId(0),
    val knowledgePoint: KnowledgePointId,
    val name: String,
)
{
    companion object
    {
        val example = SectionType(SectionTypeId(1), KnowledgePointId(1), "a subject type")
    }
}