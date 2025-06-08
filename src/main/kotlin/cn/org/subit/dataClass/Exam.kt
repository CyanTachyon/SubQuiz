package cn.org.subit.dataClass

import cn.org.subit.plugin.contentNegotiation.QuestionAnswerSerializer
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

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
    val group: PreparationGroupId = PreparationGroupId(0),
    val name: String = "",
    val description: String = "",
    @Serializable(ExamSections::class)
    val sections: List<Section<@Contextual Any, Nothing?, String>> = emptyList(),
    val available: Boolean = false,
)
{
    fun convertSectionIds() = copy(sections = sections.map { it.copy(id = SectionId(- this.id.value)) })
    companion object
    {
        val example = Exam(
            id = ExamId(1),
            group = PreparationGroupId(1),
            name = "a exam",
            description = "a exam description",
            sections = listOf(Section.example.withoutUserAnswer())
        )

        class ExamSections: KSerializer<List<Section<Any, Nothing?, String>>> by ser

        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        val ser = ListSerializer(
            Section.serializer(
                QuestionAnswerSerializer,
                NothingSerializer().nullable,
                String.serializer()
            )
        )
    }
}