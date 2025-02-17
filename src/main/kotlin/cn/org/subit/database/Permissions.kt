package cn.org.subit.database

import cn.org.subit.dataClass.Permission
import cn.org.subit.dataClass.Slice
import cn.org.subit.dataClass.SubjectId
import cn.org.subit.dataClass.UserId
import cn.org.subit.database.utils.asSlice
import cn.org.subit.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.update

class Permissions: SqlDao<Permissions.PermissionTable>(PermissionTable)
{
    object PermissionTable: IdTable<Long>("permissions")
    {
        override val id = long("id").autoIncrement().entityId()
        val user = reference("user", Users.UsersTable)
        val subject = reference("subject", Subjects.SubjectTable)
        val permission = enumeration<Permission>("permission").default(Permission.NORMAL)
        override val primaryKey = PrimaryKey(id)
    }

    suspend fun getPermission(user: UserId, subject: SubjectId) = query()
    {
        select(permission)
            .andWhere { table.user eq user }
            .andWhere { table.subject eq subject }
            .singleOrNull()
            ?.get(permission)
            ?: Permission.NORMAL
    }

    suspend fun setPermission(user: UserId, subject: SubjectId, permission: Permission) = query()
    {
        val b = update(where = { table.user eq user and (table.subject eq subject) })
        {
            it[table.user] = user
            it[table.subject] = subject
            it[table.permission] = permission
        } > 0
        if (!b) insertIgnore()
        {
            it[table.user] = user
            it[table.subject] = subject
            it[table.permission] = permission
        }
    }

    suspend fun getAdmins(subject: SubjectId, begin: Long, count: Int): Slice<Pair<UserId, Permission>> = query()
    {
        select(user, permission)
            .andWhere { table.subject eq subject }
            .andWhere { table.permission greaterEq Permission.ADMIN }
            .asSlice(begin, count)
            .map { it[table.user].value to it[table.permission] }
    }


}