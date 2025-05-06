package cn.org.subit.database

import cn.org.subit.dataClass.PreparationGroup
import cn.org.subit.dataClass.PreparationGroupId
import cn.org.subit.dataClass.SubjectId
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class PreparationGroups: SqlDao<PreparationGroups.PreparationGroupTable>(PreparationGroupTable)
{
    object PreparationGroupTable: IdTable<PreparationGroupId>("preparation_groups")
    {
        override val id = preparationGroupId("id").autoIncrement().entityId()
        val subject = reference("subject", Subjects.SubjectTable).index()
        val name = varchar("name", 255).uniqueIndex()
        val description = text("description")
        val time = timestamp("time").defaultExpression(CurrentTimestamp)
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow): PreparationGroup =
        PreparationGroup(
            id = row[table.id].value,
            subject = row[table.subject].value,
            name = row[table.name],
            description = row[table.description],
            time = row[table.time].toEpochMilliseconds(),
        )

    suspend fun getPreparationGroup(id: PreparationGroupId): PreparationGroup? = query()
    {
        selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getPreparationGroup(name: String): PreparationGroup? = query()
    {
        selectAll()
            .where { table.name eq name }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getPreparationGroups(subject: SubjectId): List<PreparationGroup> = query()
    {
        selectAll()
            .where { table.subject eq subject }
            .orderBy(table.time to SortOrder.ASC)
            .toList()
            .map(::deserialize)
    }

    suspend fun newPreparationGroup(
        subject: SubjectId,
        name: String,
        description: String,
    ): PreparationGroupId = query()
    {
        val id = insertAndGetId()
        {
            it[table.subject] = subject
            it[table.name] = name
            it[table.description] = description
        }.value
        id
    }

    suspend fun updatePreparationGroup(
        id: PreparationGroupId,
        name: String,
        description: String,
    ): Boolean = query()
    {
        update({ table.id eq id })
        {
            it[table.name] = name
            it[table.description] = description
        } > 0
    }
}