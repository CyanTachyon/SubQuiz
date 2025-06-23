package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.database.Classes
import moe.tachyon.quiz.database.KnowledgePoints
import moe.tachyon.quiz.database.SectionTypes
import moe.tachyon.quiz.database.Sections
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Suppress("unused")
@JvmInline
@Serializable
value class ExamId(val value: Long): Comparable<ExamId>
{
    override fun compareTo(other: ExamId): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
    companion object
    {
        fun String.toExamId() = ExamId(toLong())
        fun String.toExamIdOrNull() = toLongOrNull()?.let(::ExamId)
        fun Number.toExamId() = ExamId(toLong())
    }
}

@Serializable
data class Exam(
    val id: ExamId = ExamId(0),
    val clazz: ClassId = ClassId(0),
    val name: String = "",
    val description: String = "",
    val sections: List<SectionId> = emptyList(),
    val available: Boolean = false,
)
{
    companion object: KoinComponent
    {
        val example = Exam(
            id = ExamId(1),
            clazz = ClassId(1),
            name = "a exam",
            description = "a exam description",
            sections = listOf(SectionId(1), SectionId(2)),
        )

        private val classes: Classes by inject()
        private val sections: Sections by inject()
        private val sectionTypes: SectionTypes by inject()
        private val knowledgePoints: KnowledgePoints by inject()
    }

    suspend fun check(): String?
    {
        val clazz = classes.getClassInfo(this.clazz) ?: return "班级不存在"
        val group = clazz.group
        if (this.sections.size != this.sections.toSet().size) return "题目重复"
        this.sections.forEach()
        {
            val section = Exam.sections.getSection(it) ?: return "题目不存在"
            val type = sectionTypes.getSectionType(section.type) ?: return "题目不存在"
            val kp = knowledgePoints.getKnowledgePoint(type.knowledgePoint) ?: return "题目不存在"
            if (kp.group != group) return "题目 $it 不属于班级 ${clazz.name} 的准备组"
        }
        return null
    }
}