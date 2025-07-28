package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Contextual
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import moe.tachyon.quiz.plugin.contentNegotiation.QuestionAnswerSerializer
import moe.tachyon.quiz.utils.ai.ChatMessages

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
data class Chat(
    val id: ChatId,
    val user: UserId,
    val title: String,
    @Serializable(SectionSerializer::class)
    val section: Section<@Contextual Any, @Contextual Any, String>?,
    val histories: ChatMessages,
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
            title = "示例聊天",
            section = Section.example,
            histories = emptyList(),
            hash = "example-hash",
            banned = false,
        )
    }
}