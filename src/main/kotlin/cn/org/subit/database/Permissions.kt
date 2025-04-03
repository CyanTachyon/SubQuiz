package cn.org.subit.database

import cn.org.subit.dataClass.Permission
import cn.org.subit.dataClass.Slice
import cn.org.subit.dataClass.SubjectId
import cn.org.subit.dataClass.UserId
import cn.org.subit.database.utils.asSlice
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.upsert

class Permissions: SqlDao<Permissions.PermissionTable>(PermissionTable)
{
    object PermissionTable: CompositeIdTable("permissions")
    {
        val user = reference("user", Users.UsersTable, ReferenceOption.CASCADE, ReferenceOption.CASCADE).index()
        val subject = reference("subject", Subjects.SubjectTable, ReferenceOption.CASCADE, ReferenceOption.CASCADE).index()
        val permission = enumeration<Permission>("permission").default(Permission.NORMAL)
        override val primaryKey = PrimaryKey(user, subject)

        init
        {
            addIdColumn(user)
            addIdColumn(subject)
        }
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
        upsert()
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