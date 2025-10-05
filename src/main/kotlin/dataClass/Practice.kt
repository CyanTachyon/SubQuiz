package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.database.Classes
import moe.tachyon.quiz.database.KnowledgePoints
import moe.tachyon.quiz.database.Sections
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Serializable
data class Practice(
    val id: PracticeId,
    val clazz: ClassId,
    val name: String,
    val description: String,
    val available: Boolean,
    val knowledgePoints: List<KnowledgePointId>,
    val sectionCount: Int,
    val accuracy: Double,
    val dueDate: Long?,
)
{
    companion object: KoinComponent
    {
        val example = Practice(
            id = PracticeId(1),
            clazz = ClassId(1),
            name = "a practice",
            description = "a practice description",
            available = true,
            knowledgePoints = listOf(KnowledgePointId(1), KnowledgePointId(2)),
            sectionCount = 10,
            accuracy = 0.8,
            dueDate = null,
        )

        private val classes: Classes by inject()
        private val knowledgePoints: KnowledgePoints by inject()
        private val sections: Sections by inject()
    }

    suspend fun check(): String?
    {
        if (name.isBlank()) return "名称不能为空"
        if (sectionCount <= 0) return "题目数量必须大于0"
        if (accuracy !in 0.0..1.0) return "正确率必须在0到1之间"
        if (available && knowledgePoints.isEmpty()) return "可用的练习必须关联知识点"
        if (knowledgePoints.isEmpty()) return null
        val group = classes.getClassInfo(this.clazz)?.group ?: return "班级不存在"
        val kps = knowledgePoints.map { Practice.knowledgePoints.getKnowledgePoint(it) }
        val invalidKp = kps.indexOfFirst { it == null || it.group != group }
        if (invalidKp != -1) return "知识点不存在或不属于该备课组"
        if (available)
        {
            val count = sections.getSectionCount(knowledgePoints)
            if (count < sectionCount) return "您选择的知识点仅有 $count 道题目，无法创建包含 $sectionCount 道题目的练习"
        }
        return null
    }
}