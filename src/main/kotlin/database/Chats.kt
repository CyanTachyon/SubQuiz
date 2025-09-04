package moe.tachyon.quiz.database

import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.dataClass.Section
import moe.tachyon.quiz.dataClass.Slice
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.utils.asSlice
import moe.tachyon.quiz.database.utils.singleOrNull
import moe.tachyon.quiz.plugin.contentNegotiation.dataJson
import kotlinx.serialization.serializer
import moe.tachyon.quiz.utils.ai.ChatMessage
import moe.tachyon.quiz.utils.ai.ChatMessages
import moe.tachyon.quiz.utils.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.ContentNode
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import kotlin.Any

class Chats: SqlDao<Chats.ChatTable>(ChatTable)
{
    object ChatTable: IdTable<ChatId>("chats")
    {
        override val id = chatId("id").autoIncrement().entityId()
        val user = reference("user", Users.UserTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val title = varchar("title", 128).default("新建对话")
        val section = jsonb<Section<Any, Any, JsonElement>>("section", dataJson, dataJson.serializersModule.serializer()).nullable()
        val histories = jsonb<ChatMessages>("histories", dataJson, dataJson.serializersModule.serializer())
        val hash = varchar("hash", 64).index()
        val banned = bool("banned").default(false)
        val lastModified = timestamp("last_modified").clientDefault { Clock.System.now() }.defaultExpression(CurrentTimestamp).index()
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow): Chat =
        Chat(
            id = row[table.id].value,
            user = row[table.user].value,
            title = row[table.title],
            section = row[table.section],
            histories = row[table.histories],
            hash = row[table.hash],
            banned = row[table.banned],
        )

    suspend fun createChat(
        user: UserId,
        section: Section<Any, Any, JsonElement>?,
        hash: String = System.currentTimeMillis().toString(36),
    ): Chat = query()
    {
        val id = insertAndGetId()
        {
            it[table.user] = user
            it[table.section] = section
            it[table.histories] = ChatMessages.empty()
            it[table.hash] = hash
            it[table.banned] = false
            it[table.lastModified] = Clock.System.now()
        }.value
        Chat(
            id = id,
            user = user,
            title = "新建对话",
            section = section,
            histories = ChatMessages.empty(),
            hash = hash,
            banned = false,
        )
    }

    suspend fun getChat(id: ChatId): Chat? = query()
    {
        selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun deleteChat(id: ChatId, user: UserId) = query()
    {
        deleteWhere { (table.id eq id) and (table.user eq user)  } > 0
    }

    suspend fun getChats(
        user: UserId,
        begin: Long,
        count: Int,
    ): Slice<Chat> = query()
    {
        select(table.columns - table.histories)
            .where { table.user eq user }
            .orderBy(table.lastModified to SortOrder.DESC, table.id to SortOrder.DESC)
            .asSlice(begin, count)
            .map {
                Chat(
                    it[table.id].value,
                    it[table.user].value,
                    it[table.title],
                    null,
                    ChatMessages.empty(),
                    it[table.hash],
                    it[table.banned],
                )
            }
    }

    suspend fun updateHistory(
        chatId: ChatId,
        histories: ChatMessages,
        newHash: String? = null,
        ban: Boolean = false,
    ): Unit = query()
    {
        update({ table.id eq chatId })
        {
            it[table.histories] = histories.removeUnsupportedChars()
            if (newHash != null) it[table.hash] = newHash
            if (ban) it[table.banned] = true
            it[table.lastModified] = Clock.System.now()
        }
    }

    suspend fun updateName(
        chatId: ChatId,
        name: String,
    ): Unit = query()
    {
        update({ table.id eq chatId })
        {
            it[table.title] = name
            it[table.lastModified] = Clock.System.now()
        }
    }

    suspend fun checkHash(
        chatId: ChatId,
        hash: String,
    ): Boolean = query()
    {
        select(table.hash)
            .where { table.id eq chatId }
            .singleOrNull()
            ?.get(table.hash) == hash
    }

    /**
     * 删除不支持的字符，防止数据库报错
     */
    private fun ChatMessages.removeUnsupportedChars(): ChatMessages
    {
        val unsupportedChars = setOf('\u0000')
        val cleanedMessages = this.map()
        { message ->
            ChatMessage(
                role = message.role,
                content = message.content.removeUnsupportedChars(unsupportedChars),
                reasoningContent = message.reasoningContent.removeUnsupportedChars(unsupportedChars),
                toolCallId = message.toolCallId.removeUnsupportedChars(unsupportedChars),
                toolCalls = message.toolCalls.map()
                {
                    ChatMessage.ToolCall(
                        it.id.removeUnsupportedChars(unsupportedChars),
                        it.name.removeUnsupportedChars(unsupportedChars),
                        it.arguments.removeUnsupportedChars(unsupportedChars),
                    )
                },
                showingType = message.showingType,
            )
        }
        return cleanedMessages.toChatMessages()
    }

    private fun Content.removeUnsupportedChars(unsupportedChars: Set<Char>): Content =
        map()
        {
            when (it)
            {
                is ContentNode.File  -> ContentNode.file(it.file.filename.removeUnsupportedChars(unsupportedChars), it.file.url.removeUnsupportedChars(unsupportedChars))
                is ContentNode.Image -> ContentNode.image(it.image.url.removeUnsupportedChars(unsupportedChars))
                is ContentNode.Text  -> ContentNode.text(it.text.removeUnsupportedChars(unsupportedChars))
            }
        }.let(::Content)
    
    private fun String.removeUnsupportedChars(unsupportedChars: Set<Char>): String =
        this.filterNot { c -> c in unsupportedChars }
}