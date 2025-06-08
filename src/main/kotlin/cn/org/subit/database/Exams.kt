package cn.org.subit.database

import cn.org.subit.dataClass.Exam
import cn.org.subit.dataClass.ExamId
import cn.org.subit.dataClass.PreparationGroupId
import cn.org.subit.dataClass.Section
import cn.org.subit.database.utils.singleOrNull
import cn.org.subit.plugin.contentNegotiation.dataJson
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class Exams: SqlDao<Exams.ExamTable>(ExamTable)
{
    object ExamTable: IdTable<ExamId>("exams")
    {
        override val id = examId("id").autoIncrement().entityId()
        val group = reference("group", PreparationGroups.PreparationGroupTable, ReferenceOption.CASCADE, ReferenceOption.CASCADE).index()
        val name = varchar("name", 255).index()
        val description = text("description").index()
        val sections = jsonb<List<Section<Any, Nothing?, String>>>("sections", dataJson, dataJson.serializersModule.serializer())
        val available = bool("available").default(false)
        override val primaryKey = PrimaryKey(id)

        init
        {
            uniqueIndex(group, name)
        }
    }

    private fun deserialize(row: ResultRow) = Exam(
        id = row[table.id].value,
        group = row[table.group].value,
        name = row[table.name],
        description = row[table.description],
        sections = row[table.sections],
        available = row[table.available],
    )

    suspend fun getExam(id: ExamId): Exam? = query()
    {
        selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getExams(group: PreparationGroupId): List<Exam> = query()
    {
        selectAll()
            .where { table.group eq group }
            .orderBy(table.name)
            .toList()
            .map(::deserialize)
    }

    suspend fun addExam(exam: Exam): ExamId? = query()
    {
        val (_, group, name, description, sections, available) = exam
        insertIgnoreAndGetId()
        {
            it[table.group] = group
            it[table.name] = name
            it[table.description] = description
            it[table.sections] = sections
            it[table.available] = available
        }?.value
    }

    suspend fun updateExam(exam: Exam): Boolean = query()
    {
        val (id, group, name, description, sections) = exam
        update({ table.id eq id })
        {
            it[table.group] = group
            it[table.name] = name
            it[table.description] = description
            it[table.sections] = sections
        } > 0
    }
}