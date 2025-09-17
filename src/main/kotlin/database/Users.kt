package moe.tachyon.quiz.database

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import moe.tachyon.quiz.dataClass.DatabaseUser
import moe.tachyon.quiz.dataClass.Permission
import moe.tachyon.quiz.dataClass.Slice
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.utils.asSlice
import moe.tachyon.quiz.database.utils.single
import moe.tachyon.quiz.database.utils.singleOrNull
import moe.tachyon.quiz.plugin.contentNegotiation.dataJson
import moe.tachyon.quiz.utils.ai.TokenUsage
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.json.jsonb
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class Users: SqlDao<Users.UserTable>(UserTable)
{
    /**
     * 用户信息表
     */
    object UserTable: IdTable<UserId>("users")
    {
        override val id = userId("id").entityId()
        val permission = enumeration<Permission>("permission").default(Permission.NORMAL)
        val tokenUsage = jsonb<TokenUsage>("token_usage", dataJson, dataJson.serializersModule.serializer()).default(TokenUsage())
        val globalMemory = jsonb<Map<String, String>>("global_memory", dataJson, dataJson.serializersModule.serializer()).default(emptyMap())
        val customSettings = jsonb<Map<String, JsonElement>>("custom_settings", dataJson, dataJson.serializersModule.serializer()).default(emptyMap())
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = DatabaseUser(
        id = row[UserTable.id].value,
        permission = row[UserTable.permission],
        tokenUsage = row[UserTable.tokenUsage],
    )

    suspend fun changePermission(id: UserId, permission: Permission): Boolean = query()
    {
        update({ UserTable.id eq id }) { it[UserTable.permission] = permission } > 0
    }

    suspend fun getOrCreateUser(id: UserId): DatabaseUser = query()
    {
        insertIgnore { it[UserTable.id] = id }
        select(table.id, table.permission,table. tokenUsage).where { UserTable.id eq id }.single().let(::deserialize)
    }

    suspend fun getAdmins(begin: Long, count: Int): Slice<Pair<UserId, Permission>> = query()
    {
        select(id, permission)
            .andWhere { permission greaterEq Permission.ADMIN }
            .asSlice(begin, count)
            .map { it[id].value to it[permission] }
    }

    suspend fun addTokenUsage(id: UserId, usage: TokenUsage): Boolean = query()
    {
        val tokenUsage = select(tokenUsage).where { UserTable.id eq id }.singleOrNull()?.get(tokenUsage) ?: return@query false
        update({ UserTable.id eq id }) { it[UserTable.tokenUsage] = tokenUsage + usage } > 0
    }

    suspend fun getGlobalMemory(id: UserId): Map<String, String> = query()
    {
        select(globalMemory).where { UserTable.id eq id }.singleOrNull()?.get(globalMemory) ?: emptyMap()
    }

    suspend fun setGlobalMemory(id: UserId, key: String, value: String): Boolean = query()
    {
        val currentMemory = select(globalMemory).where { UserTable.id eq id }.singleOrNull()?.get(globalMemory) ?: emptyMap()
        val updatedMemory = currentMemory + (key to value)
        update({ UserTable.id eq id }) { it[globalMemory] = updatedMemory } > 0
    }

    suspend fun removeGlobalMemory(id: UserId, key: String): Boolean = query()
    {
        val currentMemory = select(globalMemory).where { UserTable.id eq id }.singleOrNull()?.get(globalMemory) ?: emptyMap()
        val updatedMemory = currentMemory - key
        update({ UserTable.id eq id }) { it[globalMemory] = updatedMemory } > 0
    }

    suspend inline fun <reified T: Any> getCustomSetting(id: UserId, key: String): T? =
        getCustomSetting(id, key, typeOf<T>())

    suspend fun <T: Any> getCustomSetting(id: UserId, key: String, type: KType): T? = query()
    {
        val settings = select(customSettings).where { UserTable.id eq id }.singleOrNull()?.get(customSettings) ?: return@query null
        val value = settings[key] ?: return@query null
        @Suppress("UNCHECKED_CAST")
        dataJson.decodeFromJsonElement(dataJson.serializersModule.serializer(type), value) as T
    }

    suspend inline fun <reified T: Any> setCustomSetting(id: UserId, key: String, value: T?): Boolean =
        setCustomSetting(id, key, value, typeOf<T>())

    suspend fun <T: Any> setCustomSetting(id: UserId, key: String, value: T?, type: KType): Boolean = query()
    {
        val currentSettings = select(customSettings).where { UserTable.id eq id }.singleOrNull()?.get(customSettings) ?: emptyMap()
        val updatedSettings =
            if (value == null) currentSettings - key
            else currentSettings + (key to dataJson.encodeToJsonElement(dataJson.serializersModule.serializer(type), value))
        update({ UserTable.id eq id }) { it[customSettings] = updatedSettings } > 0
    }
}