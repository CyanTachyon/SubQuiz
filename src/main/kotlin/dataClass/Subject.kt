package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class Subject(
    val id: SubjectId,
    val name: String,
    val description: String,
)
{
    companion object
    {
        val example = Subject(
            SubjectId(0),
            "Subject Name",
            "subject description"
        )
    }
}