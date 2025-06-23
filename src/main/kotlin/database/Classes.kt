package moe.tachyon.quiz.database

import moe.tachyon.quiz.dataClass.Class
import moe.tachyon.quiz.dataClass.ClassId
import moe.tachyon.quiz.dataClass.PreparationGroupId
import moe.tachyon.quiz.dataClass.Slice
import moe.tachyon.quiz.database.utils.asSlice
import moe.tachyon.quiz.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class Classes: SqlDao<Classes.ClassTable>(ClassTable)
{
    object ClassTable: IdTable<ClassId>("classes")
    {
        override val id = classId("id").autoIncrement().entityId()
        val name: Column<String> = varchar("name", 255)
        val group = reference("group", PreparationGroups.PreparationGroupTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        override val primaryKey = PrimaryKey(id)

        init
        {
            uniqueIndex(name, group)
        }
    }

    private fun deserialize(row: ResultRow) =
        Class(
            id = row[table.id].value,
            name = row[table.name],
            group = row[table.group].value,
        )

    suspend fun getClassInfo(id: ClassId): Class? = query()
    {
        selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getClasses(preparationGroup: PreparationGroupId?, begin: Long, count: Int): Slice<Class> = query()
    {
        selectAll()
            .let { if (preparationGroup != null) it.where { table.group eq preparationGroup } else it }
            .orderBy(table.name to SortOrder.ASC)
            .asSlice(begin, count)
            .map(::deserialize)
    }

    suspend fun createClass(
        name: String,
        group: PreparationGroupId,
    ): ClassId? = query()
    {
        table.insertIgnoreAndGetId()
        {
            it[table.name] = name
            it[table.group] = group
        }?.value
    }

    suspend fun updateClass(
        id: ClassId,
        name: String,
        group: PreparationGroupId,
    ): Boolean = query()
    {
        table.update({ table.id eq id })
        {
            it[table.name] = name
            it[table.group] = group
        } > 0
    }

    suspend fun deleteClass(id: ClassId): Boolean = query()
    {
        table.deleteWhere { table.id eq id } > 0
    }
}