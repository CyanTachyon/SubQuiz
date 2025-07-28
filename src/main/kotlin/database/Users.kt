package moe.tachyon.quiz.database

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
import org.jetbrains.exposed.sql.json.extract
import org.jetbrains.exposed.sql.json.jsonb

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
        selectAll().where { UserTable.id eq id }.single().let(::deserialize)
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

    suspend fun getUserOrderByTokenUsage(begin: Long, count: Int): Slice<DatabaseUser> = query()
    {
        selectAll()
            .orderBy(tokenUsage.extract<Long>("total_tokens", toScalar = false) to SortOrder.DESC)
            .asSlice(begin, count)
            .map(::deserialize)
    }
}