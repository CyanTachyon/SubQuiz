package moe.tachyon.quiz.database

import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.Slice
import moe.tachyon.quiz.database.utils.asSlice
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.koin.core.component.inject

class ClassMembers: SqlDao<ClassMembers.ClassMemberTable>(ClassMemberTable)
{
    object ClassMemberTable: CompositeIdTable("class_members")
    {
        val clazz = reference("class", Classes.ClassTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val user = reference("user", Users.UserTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).nullable().default(null).index()
        val realName = varchar("real_name", 255).entityId().index()
        val studentId = varchar("student_id", 64).entityId().index()

        override val primaryKey = PrimaryKey(clazz, studentId)

        init
        {
            addIdColumn(clazz)
            addIdColumn(studentId)

            uniqueIndex(clazz, realName)
        }
    }

    private val classes: Classes by inject()

    suspend fun getClassMembers(clazz: ClassId): List<ClassMember> = query()
    {
        select(user, realName, studentId)
            .where { ClassMemberTable.clazz eq clazz }
            .map {
                ClassMember(
                    user = it[user]?.value,
                    seiue = SsoUserFull.Seiue(
                        realName = it[realName].value,
                        studentId = it[studentId].value,
                        archived = false,
                    ),
                )
            }
    }

    suspend fun insertMembers(clazz: ClassId, seiues: List<SsoUserFull.Seiue>) = query()
    {
        batchInsert(seiues, true)
        {
            this[ClassMemberTable.clazz] = clazz
            this[ClassMemberTable.realName] = it.realName
            this[ClassMemberTable.studentId] = it.studentId
        }
    }

    suspend fun removeMembers(clazz: ClassId, studentIds: List<String>) = query()
    {
        table.deleteWhere { (table.clazz eq clazz) and (table.studentId inList studentIds) } > 0
    }

    suspend fun updateUsers(user: UserId, studentIds: List<String>): Unit = query()
    {
        table.update({
            (table.studentId inList studentIds) and (table.user.isNull() or (table.user neq user))
        })
        {
            it[table.user] = user
        }
        table.update({ (table.user eq user) and (table.studentId notInList studentIds) })
        {
            it[table.user] = null
        }
    }

    suspend fun getUserClasses(
        user: UserId,
        preparationGroup: PreparationGroupId?,
        begin: Long,
        count: Int
    ): Slice<Class> = query()
    {
        table
            .join(classes.table, JoinType.LEFT, table.clazz, classes.table.id)
            .select(table.columns + classes.table.name + classes.table.group)
            .andWhere { table.user eq user }
            .apply { if (preparationGroup != null) andWhere { classes.table.group eq preparationGroup } }
            .orderBy(classes.table.name to SortOrder.ASC)
            .asSlice(begin, count)
            .map()
            {
                Class(
                    id = it[table.clazz].value,
                    name = it[classes.table.name],
                    group = it[classes.table.group].value,
                )
            }
    }

    suspend fun inClass(
        user: UserId,
        clazz: ClassId
    ): Boolean = query()
    {
        table.selectAll().where { (table.clazz eq clazz) and (table.user eq user) }.count() > 0
    }
}