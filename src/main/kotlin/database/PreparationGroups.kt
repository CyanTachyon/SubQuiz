package moe.tachyon.quiz.database

import moe.tachyon.quiz.dataClass.PreparationGroup
import moe.tachyon.quiz.dataClass.PreparationGroupId
import moe.tachyon.quiz.dataClass.SubjectId
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class PreparationGroups: SqlDao<PreparationGroups.PreparationGroupTable>(PreparationGroupTable)
{
    object PreparationGroupTable: IdTable<PreparationGroupId>("preparation_groups")
    {
        override val id = preparationGroupId("id").autoIncrement().entityId()
        val subject = reference("subject", Subjects.SubjectTable).index()
        val name = varchar("name", 255).index()
        val description = text("description")
        val time = timestamp("time").defaultExpression(CurrentTimestamp)
        override val primaryKey = PrimaryKey(id)

        init
        {
            uniqueIndex(subject, name)
        }
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

    suspend fun getPreparationGroup(subject: SubjectId, name: String): PreparationGroup? = query()
    {
        selectAll()
            .andWhere { table.subject eq subject }
            .andWhere { table.name eq name }
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