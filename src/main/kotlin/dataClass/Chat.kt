package moe.tachyon.quiz.dataClass

import moe.tachyon.quiz.plugin.contentNegotiation.QuestionAnswerSerializer
import moe.tachyon.quiz.utils.ai.Role
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer

@Suppress("unused")
@JvmInline
@Serializable
value class ChatId(val value: Long): Comparable<ChatId>
{
    override fun compareTo(other: ChatId): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
    companion object
    {
        fun String.toChatId() = ChatId(toLong())
        fun String.toChatIdOrNull() = toLongOrNull()?.let(::ChatId)
        fun Number.toChatId() = ChatId(toLong())
    }
}

@Serializable
data class ChatMessage(
    val role: Role = Role.USER,
    val content: String = "",
    @SerialName("reasoning_content")
    val reasoningContent: String = "",
)

@Serializable
data class Chat(
    val id: ChatId,
    val user: UserId,
    @Serializable(SectionSerializer::class)
    val section: Section<@Contextual Any, @Contextual Any, String>?,
    val histories: List<ChatMessage>,
    val hash: String,
    val banned: Boolean,
)
{
    companion object
    {
        @OptIn(InternalSerializationApi::class)
        private val ser = Section.serializer(
            QuestionAnswerSerializer,
            QuestionAnswerSerializer,
            String.serializer()
        )
        private class SectionSerializer: KSerializer<Section<Any, Any, String>> by ser

        val example = Chat(
            id = ChatId(1),
            user = UserId(1),
            section = Section.example,
            histories = emptyList(),
            hash = "example-hash",
            banned = false,
        )
    }
}