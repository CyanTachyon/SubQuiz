package cn.org.subit.database

import cn.org.subit.dataClass.DatabaseUser
import cn.org.subit.dataClass.Permission
import cn.org.subit.dataClass.Slice
import cn.org.subit.dataClass.UserId
import cn.org.subit.database.utils.asSlice
import cn.org.subit.database.utils.single
import cn.org.subit.database.utils.singleOrNull
import cn.org.subit.plugin.contentNegotiation.dataJson
import cn.org.subit.utils.ai.AI
import cn.org.subit.utils.ai.AiResponse
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.json.extract
import org.jetbrains.exposed.sql.json.jsonb

class Users: SqlDao<Users.UsersTable>(UsersTable)
{
    /**
     * 用户信息表
     */
    object UsersTable: IdTable<UserId>("users")
    {
        override val id = userId("id").entityId()
        val permission = enumeration<Permission>("permission").default(Permission.NORMAL)
        val tokenUsage = jsonb<AiResponse.Usage>("token_usage", dataJson, dataJson.serializersModule.serializer()).default(AiResponse.Usage())
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = DatabaseUser(
        id = row[UsersTable.id].value,
        permission = row[UsersTable.permission],
        tokenUsage = row[UsersTable.tokenUsage],
    )

    suspend fun changePermission(id: UserId, permission: Permission): Boolean = query()
    {
        update({ UsersTable.id eq id }) { it[UsersTable.permission] = permission } > 0
    }

    suspend fun getOrCreateUser(id: UserId): DatabaseUser = query()
    {
        insertIgnore { it[UsersTable.id] = id }
        selectAll().where { UsersTable.id eq id }.single().let(::deserialize)
    }

    suspend fun getAdmins(begin: Long, count: Int): Slice<Pair<UserId, Permission>> = query()
    {
        select(id, permission)
            .andWhere { permission greaterEq Permission.ADMIN }
            .asSlice(begin, count)
            .map { it[id].value to it[permission] }
    }

    suspend fun addTokenUsage(id: UserId, usage: AiResponse.Usage): Boolean = query()
    {
        val tokenUsage = select(tokenUsage).where { UsersTable.id eq id }.singleOrNull()?.get(tokenUsage) ?: return@query false
        update({ UsersTable.id eq id }) { it[UsersTable.tokenUsage] = tokenUsage + usage } > 0
    }

    suspend fun getUserOrderByTokenUsage(begin: Long, count: Int): Slice<DatabaseUser> = query()
    {
        selectAll()
            .orderBy(tokenUsage.extract<Long>("total_tokens", toScalar = false) to SortOrder.DESC)
            .asSlice(begin, count)
            .map(::deserialize)
    }
}