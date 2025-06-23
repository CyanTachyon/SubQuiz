package moe.tachyon.quiz.database

import kotlinx.serialization.serializer
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.database.utils.singleOrNull
import moe.tachyon.quiz.plugin.contentNegotiation.dataJson
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.json.jsonb
import org.koin.core.component.inject

class Exams: SqlDao<Exams.ExamTable>(ExamTable)
{
    object ExamTable: IdTable<ExamId>("exams")
    {
        override val id = examId("id").autoIncrement().entityId()
        val clazz = reference("class", Classes.ClassTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val name = varchar("name", 255).index()
        val description = text("description").index()
        val sections = jsonb<List<SectionId>>("sections", dataJson, dataJson.serializersModule.serializer())
        val available = bool("available").default(false)
        override val primaryKey = PrimaryKey(id)

        init
        {
            uniqueIndex(clazz, name)
        }
    }

    private val quizzes: Quizzes by inject()
    private val classMembers: ClassMembers by inject()

    private fun deserialize(row: ResultRow) = Exam(
        id = row[table.id].value,
        clazz = row[table.clazz].value,
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

    suspend fun getExams(clazz: ClassId): List<Exam> = query()
    {
        selectAll()
            .where { table.clazz eq clazz }
            .orderBy(table.name)
            .toList()
            .map(::deserialize)
    }

    suspend fun addExam(exam: Exam): ExamId? = query()
    {
        val (_, clazz, name, description, sections, available) = exam
        insertIgnoreAndGetId()
        {
            it[table.clazz] = clazz
            it[table.name] = name
            it[table.description] = description
            it[table.sections] = sections
            it[table.available] = available
        }?.value
    }

    suspend fun updateExam(exam: Exam): Boolean = query()
    {
        val (id, clazz, name, description, sections) = exam
        update({ table.id eq id })
        {
            it[table.clazz] = clazz
            it[table.name] = name
            it[table.description] = description
            it[table.sections] = sections
        } > 0
    }

    suspend fun deleteExam(id: ExamId): Boolean = query()
    {
        table.deleteWhere { table.id eq id } > 0
    }

    suspend fun getExamScores(exam: ExamId): List<Pair<ClassMember, List<List<Boolean?>>?>> = query()
    {
        classMembers.table
            .join(quizzes.table, JoinType.LEFT, classMembers.table.user, quizzes.table.user)
            .select(classMembers.table.columns + quizzes.table.correct)
            .andWhere { classMembers.table.user.isNotNull() }
            .andWhere { quizzes.table.exam eq exam }
            .orderBy(classMembers.table.studentId to SortOrder.ASC)
            .toList()
            .map {
                val member = ClassMember(
                    user = it[classMembers.table.user]?.value,
                    seiue = SsoUserFull.Seiue(
                        studentId = it[classMembers.table.studentId].value,
                        realName = it[classMembers.table.realName].value,
                        archived = false,
                    )
                )
                member to it[quizzes.table.correct]
            }
    }
}