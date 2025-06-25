package moe.tachyon.quiz.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.UserId.Companion.toUserId
import moe.tachyon.quiz.database.utils.singleOrNull
import moe.tachyon.quiz.plugin.contentNegotiation.dataJson
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.transactions.TransactionManager
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

    // 作答信息
    @Serializable
    data class ExamScore(
        val member: ClassMember,
        val sections: List<SectionScore>,
    )
    {
        @Serializable
        data class SectionScore(
            val id: SectionId,
            val questions: List<QuestionScore>,
        )
        {
            @Serializable
            data class QuestionScore(
                val answer: JsonElement,
                val correct: Boolean?,
            )
        }

        companion object
        {
            val example = ExamScore(
                member = ClassMember(
                    user = UserId(1),
                    seiue = SsoUserFull.Seiue(
                        realName = "张三",
                        studentId = "123456",
                        archived = false,
                    ),
                ),
                sections = listOf(
                    SectionScore(
                        id = SectionId(1),
                        questions = listOf(
                            SectionScore.QuestionScore(JsonPrimitive(0), true),
                            SectionScore.QuestionScore(JsonArray(listOf(1, 2).map(::JsonPrimitive)), false),
                        )
                    )
                )
            )
        }
    }

    suspend fun getExamScores(exam: ExamId): List<ExamScore>? = query()
    {
        @Suppress("SqlConstantExpression")
        @Language("SQL")
        val q = """
        select 
            class_members."user"     as "user",
            class_members.student_id as "student_id",
            class_members.real_name  as "name",
            jsonb_agg(
                jsonb_build_object(
                    'questions', 
                    coalesce(
                        (select jsonb_agg(jsonb_build_object('answer', e -> 'userAnswer', 'correct', e1))
                        from 
                            jsonb_array_elements(section_elem -> 'questions') with ordinality as section_elems1(e, idx) 
                            inner join
                            jsonb_array_elements(correct_elem) with ordinality as correct_elems1(e1, idx1)
                            on idx = idx1)
                    , '[]'::jsonb),
                    'id', 
                    section_elem -> 'id'
                   )
               )                        as "answer",
               id
        
        from 
            class_members 
            left join 
            quizzes 
            on quizzes."user" = class_members."user",
            
            lateral jsonb_array_elements(sections) with ordinality as section_elems(section_elem, section_idx)
            inner join
            lateral jsonb_array_elements("correct") with ordinality as correct_elems(correct_elem, correct_idx)
            on section_idx = correct_idx
        
        where 1=1
            and class_members."user" is not null
            and "exam" = $exam
            and quizzes.finished = true
            and "correct" is not null
        
        group by "id", class_members."class", class_members."student_id"
        order by "id"
        """.trimIndent()

        val result = TransactionManager.current().exec(q)
        {
            val res = mutableListOf<ExamScore>()
            while (it.next())
            {
                val user = it.getInt("user").toUserId()
                val studentId = it.getString("student_id")
                val name = it.getString("name")
                val answer = it.getString("answer")
                res += ExamScore(
                    member = ClassMember(
                        user = user,
                        seiue = SsoUserFull.Seiue(
                            realName = name,
                            studentId = studentId,
                            archived = false,
                        ),
                    ),
                    sections = dataJson.decodeFromString(answer)
                )
            }
            res
        }

        result
    }
}