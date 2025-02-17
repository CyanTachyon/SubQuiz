package cn.org.subit.database

import cn.org.subit.dataClass.DatabaseUser
import cn.org.subit.dataClass.Permission
import cn.org.subit.dataClass.Slice
import cn.org.subit.dataClass.UserId
import cn.org.subit.database.utils.asSlice
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*

class Users: SqlDao<Users.UsersTable>(UsersTable)
{
    /**
     * 用户信息表
     */
    object UsersTable: IdTable<UserId>("users")
    {
        override val id = userId("id").entityId()
        val permission = enumeration<Permission>("permission").default(Permission.NORMAL)
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) = DatabaseUser(
        id = row[UsersTable.id].value,
        permission = row[UsersTable.permission],
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
}