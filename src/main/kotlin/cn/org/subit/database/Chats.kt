package cn.org.subit.database

import cn.org.subit.dataClass.Chat
import cn.org.subit.dataClass.ChatId
import cn.org.subit.dataClass.ChatMessage
import cn.org.subit.dataClass.Section
import cn.org.subit.dataClass.Slice
import cn.org.subit.dataClass.UserId
import cn.org.subit.database.utils.asSlice
import cn.org.subit.database.utils.singleOrNull
import cn.org.subit.plugin.contentNegotiation.dataJson
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import kotlin.Any

class Chats: SqlDao<Chats.ChatTable>(ChatTable)
{
    object ChatTable: IdTable<ChatId>("chats")
    {
        override val id = chatId("id").autoIncrement().entityId()
        val user = reference("user", Users.UsersTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val section = jsonb<Section<Any, Any, String>>("section", dataJson, dataJson.serializersModule.serializer()).nullable()
        val histories = jsonb<List<ChatMessage>>("histories", dataJson, dataJson.serializersModule.serializer())
        val hash = varchar("hash", 64).index()
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow): Chat =
        Chat(
            id = row[table.id].value,
            user = row[table.user].value,
            section = row[table.section],
            histories = row[table.histories],
            hash = row[table.hash],
        )

    suspend fun createChat(
        user: UserId,
        section: Section<Any, Any, String>?,
        hash: String = System.currentTimeMillis().toString(36),
    ): Chat = query()
    {
        val id = insertAndGetId {
            it[ChatTable.user] = user
            it[ChatTable.section] = section
            it[ChatTable.histories] = emptyList()
            it[ChatTable.hash] = hash
        }.value
        Chat(
            id = id,
            user = user,
            section = section,
            histories = emptyList(),
            hash = hash,
        )
    }

    suspend fun getChat(id: ChatId): Chat? = query()
    {
        selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getChats(
        user: UserId,
        begin: Long,
        count: Int,
    ): Slice<Chat> = query()
    {
        select(id, table.user, section, hash)
            .where { table.user eq user }
            .orderBy(table.id to SortOrder.DESC)
            .asSlice(begin, count)
            .map {
                Chat(
                    it[table.id].value,
                    it[table.user].value,
                    it[table.section],
                    emptyList(),
                    it[table.hash]
                )
            }
    }

    suspend fun addHistory(
        chatId: ChatId,
        message: ChatMessage,
        newHash: String? = null,
    ): Unit = query()
    {
        val histories = select(table.histories)
            .where { table.id eq chatId }
            .singleOrNull()
            ?.get(table.histories)
            ?: emptyList()

        update({ table.id eq chatId })
        {
            it[table.histories] = histories + message
            if (newHash != null) it[table.hash] = newHash
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
}