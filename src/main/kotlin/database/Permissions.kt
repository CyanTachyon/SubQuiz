package moe.tachyon.quiz.database

import moe.tachyon.quiz.dataClass.Permission
import moe.tachyon.quiz.dataClass.PreparationGroupId
import moe.tachyon.quiz.dataClass.Slice
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.utils.asSlice
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.upsert

class Permissions: SqlDao<Permissions.PermissionTable>(PermissionTable)
{
    object PermissionTable: CompositeIdTable("permissions")
    {
        val user = reference("user", Users.UserTable, ReferenceOption.CASCADE, ReferenceOption.CASCADE).index()
        val group = reference("group", PreparationGroups.PreparationGroupTable, ReferenceOption.CASCADE, ReferenceOption.CASCADE).index()
        val permission = enumeration<Permission>("permission").default(Permission.NORMAL)
        override val primaryKey = PrimaryKey(user, group)

        init
        {
            addIdColumn(user)
            addIdColumn(group)
        }
    }

    suspend fun getPermission(user: UserId, group: PreparationGroupId) = query()
    {
        select(permission)
            .andWhere { table.user eq user }
            .andWhere { table.group eq group }
            .singleOrNull()
            ?.get(permission)
            ?: Permission.NORMAL
    }

    suspend fun setPermission(user: UserId, group: PreparationGroupId, permission: Permission) = query()
    {
        upsert()
        {
            it[table.user] = user
            it[table.group] = group
            it[table.permission] = permission
        }
    }

    suspend fun getAdmins(group: PreparationGroupId, begin: Long, count: Int): Slice<Pair<UserId, Permission>> = query()
    {
        select(user, permission)
            .andWhere { table.group eq group }
            .andWhere { table.permission greaterEq Permission.ADMIN }
            .asSlice(begin, count)
            .map { it[table.user].value to it[table.permission] }
    }


}