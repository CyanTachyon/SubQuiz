package moe.tachyon.quiz.database

import moe.tachyon.quiz.dataClass.PreparationGroup
import moe.tachyon.quiz.dataClass.PreparationGroupId
import moe.tachyon.quiz.dataClass.SubjectId
import moe.tachyon.quiz.dataClass.UserFull
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.dataClass.hasGlobalAdmin
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.inject
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
        val private = bool("private").default(false)
        override val primaryKey = PrimaryKey(id)

        init
        {
            uniqueIndex(subject, name)
        }
    }

    private val classes: Classes by inject()
    private val classesMembers: ClassMembers by inject()

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

    suspend fun getPreparationGroups(subject: SubjectId, user: UserFull): List<PreparationGroup> = query()
    {
        Join(table)
            .let()
            {
                if (user.hasGlobalAdmin()) it
                else it.join(
                    classes.table,
                    JoinType.LEFT,
                    additionalConstraint = { classes.table.group eq table.id }
                ).join(
                    classesMembers.table,
                    JoinType.LEFT,
                    additionalConstraint = { (classesMembers.table.studentId inList user.seiue.map { s -> s.studentId }) and (classes.table.id eq classesMembers.table.clazz) }
                )
            }.select(table.columns)
            .andWhere { table.subject eq subject }
            .groupBy(*table.columns.toTypedArray())
            .apply()
            {
                if (!user.hasGlobalAdmin())
                    having { (table.private eq false) or (classesMembers.table.studentId.count().greater(0)) }
            }
            .orderBy(table.time to SortOrder.ASC)
            .toList()
            .map(::deserialize)
    }

    suspend fun hasPermission(group: PreparationGroupId, user: UserFull): Boolean = query()
    {
        if (user.hasGlobalAdmin()) return@query true
        Join(table)
            .join(
                classes.table,
                JoinType.LEFT,
                additionalConstraint = { classes.table.group eq table.id }
            ).join(
                classesMembers.table,
                JoinType.LEFT,
                additionalConstraint = { (classesMembers.table.studentId inList user.seiue.map { s -> s.studentId }) and (classes.table.id eq classesMembers.table.clazz) }
            ).select(table.columns)
            .andWhere { table.id eq group }
            .groupBy(*table.columns.toTypedArray())
            .having { (table.private eq false) or (classesMembers.table.studentId.count().greater(0)) }
            .empty()
            .not()
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