package moe.tachyon.quiz.database

import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.Slice
import moe.tachyon.quiz.database.utils.asSlice
import moe.tachyon.quiz.database.utils.singleOrNull
import moe.tachyon.quiz.plugin.contentNegotiation.dataJson
import moe.tachyon.quiz.utils.ai.TokenUsage
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

class Quizzes: SqlDao<Quizzes.QuizTable>(QuizTable)
{
    object QuizTable: IdTable<QuizId>("quizzes")
    {
        override val id = quizId("id").autoIncrement().entityId()
        val user = reference("user", Users.UserTable).index()
        val time = timestamp("time").defaultExpression(CurrentTimestamp).index()
        val duration = long("duration").nullable().default(null)
        val sections = jsonb<List<Section<Any, Any?, JsonElement>>>("sections", dataJson, dataJson.serializersModule.serializer())
        val finished = bool("finished").default(false)
        val correct = jsonb<List<List<Boolean?>>>("correct", dataJson, dataJson.serializersModule.serializer()).nullable().default(null)
        val accuracy = double("accuracy").default(0.0)
        val tokenUsage = jsonb<TokenUsage>("token_usage", dataJson, dataJson.serializersModule.serializer()).nullable().default(null)
        val exam = reference("exam", Exams.ExamTable, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE).nullable().index()
        val practices = reference("practices", Practices.PracticeTable, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE).nullable().index()
        override val primaryKey = PrimaryKey(id)

        init
        {
            uniqueIndex(user, exam, filterCondition = { exam.isNotNull() })
            index(null, false, user, practices, filterCondition = { practices.isNotNull() })
        }
    }

    private fun deserialize(row: ResultRow): Quiz<Any, Any?, JsonElement> =
        Quiz(
            id = row[table.id].value,
            user = row[table.user].value,
            time = row[table.time].toEpochMilliseconds(),
            sections = row[table.sections],
            duration = row[table.duration],
            finished = row[table.finished],
            correct = row[table.correct],
            tokenUsage = row[table.tokenUsage],
        )

    suspend fun getUnfinishedQuizzes(
        user: UserId,
        practice: PracticeId? = null,
    ): List<Quiz<Any, Any?, JsonElement>> = query()
    {
        selectAll()
            .andWhere { finished eq false }
            .andWhere { table.user eq user }
            .apply { if (practice != null) andWhere { table.practices eq practice } }
            .orderBy(table.time, SortOrder.DESC)
            .map(::deserialize)
    }

    suspend fun getQuiz(id: QuizId): Quiz<Any, Any?, JsonElement>? = query()
    {
        selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getQuiz(user: UserId, exam: ExamId): Quiz<Any, Any?, JsonElement>? = query()
    {
        selectAll()
            .where { table.user eq user }
            .andWhere { table.exam eq exam }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getQuiz(user: UserId, practices: PracticeId): List<Quiz<Any, Any?, JsonElement>> = query()
    {
        selectAll()
            .where { table.user eq user }
            .andWhere { table.practices eq practices }
            .orderBy(table.time, SortOrder.DESC)
            .map(::deserialize)
    }

    suspend fun getQuizzes(user: UserId?, begin: Long, count: Int): Slice<Quiz<Any, Any?, JsonElement>> = query()
    {
        selectAll()
            .apply { if (user != null) andWhere { table.user eq user } }
            .orderBy(table.time, SortOrder.DESC)
            .asSlice(begin, count)
            .map(::deserialize)
    }

    suspend fun addQuiz(
        user: UserId,
        sections: List<Section<Any, Nothing?, JsonElement>>,
        exam: ExamId?,
        practices: PracticeId? = null,
    ): Quiz<Any, Nothing?, JsonElement> = query()
    {
        val time = Clock.System.now()
        val id = insertAndGetId {
            it[table.user] = user
            it[table.time] = time
            it[table.sections] = sections
            it[table.exam] = exam
            it[table.practices] = practices
        }.value
        Quiz(
            id,
            user,
            time.toEpochMilliseconds(),
            null,
            sections,
            exam,
            false,
            null,
            null,
        )
    }

    suspend fun updateQuiz(id: QuizId, finished: Boolean, duration: Long?, sections: List<Section<Any, Any?, JsonElement>>) = query()
    {
        update({ table.id eq id})
        {
            it[table.sections] = sections
            it[table.finished] = finished
            it[table.duration] = duration
            it[table.sections] = sections
        }
    }

    suspend fun updateQuizAnswerCorrect(id: QuizId, correct: List<List<Boolean?>>, tokenUsage: TokenUsage)
    {
        val q = getQuiz(id) ?: return
        if (q.sections.size != correct.size) error("Sections size must be equal")
        (q.sections zip correct).forEach()
        {
            if (it.first.questions.size != it.second.size) error("Questions size must be equal")
        }
        val total = correct.sumOf { it.count { b -> b != null } }
        val correctCount = correct.sumOf { it.count { b -> b == true } }
        query()
        {
            update({ table.id eq id })
            {
                it[table.correct] = correct
                it[table.accuracy] = if (total == 0) 0.0 else correctCount.toDouble() / total
                it[table.tokenUsage] = tokenUsage
            }
        }
    }
}