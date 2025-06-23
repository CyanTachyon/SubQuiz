package moe.tachyon.quiz.database

import moe.tachyon.quiz.dataClass.Subject
import moe.tachyon.quiz.dataClass.SubjectId
import moe.tachyon.quiz.database.utils.asSlice
import moe.tachyon.quiz.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*

class Subjects: SqlDao<Subjects.SubjectTable>(SubjectTable)
{
    object SubjectTable: IdTable<SubjectId>("subjects")
    {
        override val id = subjectId("id").autoIncrement().entityId()
        val name = text("name").uniqueIndex()
        val description = text("description")
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow) =
        Subject(
            row[table.id].value,
            row[table.name],
            row[table.description]
        )


    suspend fun createSubject(name: String, description: String) = query()
    {
        insertIgnoreAndGetId()
        {
            it[table.name] = name
            it[table.description] = description
        }?.value
    }

    suspend fun getSubject(id: SubjectId) = query()
    {
        selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getSubjects(begin: Long, count: Int) = query()
    {
        selectAll()
            .orderBy(table.id to SortOrder.ASC)
            .asSlice(begin, count)
            .map(::deserialize)
    }

    suspend fun updateSubject(id: SubjectId, name: String?, description: String?) = query()
    {
        update({ table.id eq id })
        {
            if (name != null) it[table.name] = name
            if (description != null) it[table.description] = description
        }
    }
}