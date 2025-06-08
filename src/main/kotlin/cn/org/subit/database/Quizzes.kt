package cn.org.subit.database

import cn.org.subit.dataClass.*
import cn.org.subit.dataClass.Slice
import cn.org.subit.database.utils.asSlice
import cn.org.subit.database.utils.singleOrNull
import cn.org.subit.plugin.contentNegotiation.dataJson
import cn.org.subit.utils.ai.AiResponse
import kotlinx.datetime.Clock
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.json.extract
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

class Quizzes: SqlDao<Quizzes.QuizTable>(QuizTable)
{
    object QuizTable: IdTable<QuizId>("quizzes")
    {
        override val id = quizId("id").autoIncrement().entityId()
        val user = reference("user", Users.UsersTable).index()
        val time = timestamp("time").defaultExpression(CurrentTimestamp).index()
        val duration = long("duration").nullable().default(null)
        val sections = jsonb<List<Section<Any, Any?, String>>>("sections", dataJson, dataJson.serializersModule.serializer())
        val finished = bool("finished").default(false)
        val correct = jsonb<List<List<Boolean?>>>("correct", dataJson, dataJson.serializersModule.serializer()).nullable().default(null)
        val tokenUsage = jsonb<AiResponse.Usage>("token_usage", dataJson, dataJson.serializersModule.serializer()).nullable().default(null)
        override val primaryKey = PrimaryKey(id)
    }

    private fun deserialize(row: ResultRow): Quiz<Any, Any?, String> =
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

    suspend fun getUnfinishedQuiz(user: UserId): Quiz<Any, Any?, String>? = query()
    {
        selectAll()
            .where { finished eq false }
            .andWhere { table.user eq user }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getQuiz(id: QuizId): Quiz<Any, Any?, String>? = query()
    {
        selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getQuizzes(user: UserId?, begin: Long, count: Int): Slice<Quiz<Any, Any?, String>> = query()
    {
        selectAll()
            .apply { if (user != null) andWhere { table.user eq user } }
            .orderBy(table.time, SortOrder.DESC)
            .asSlice(begin, count)
            .map(::deserialize)
    }

    suspend fun addQuiz(user: UserId, sections: List<Section<Any, Nothing?, String>>): Quiz<Any, Nothing?, String> = query()
    {
        val time = Clock.System.now()
        val id = insertAndGetId {
            it[table.user] = user
            it[table.time] = time
            it[table.sections] = sections
        }.value
        Quiz(
            id,
            user,
            time.toEpochMilliseconds(),
            null,
            sections,
            false,
            null,
            null,
        )
    }

    suspend fun updateQuiz(id: QuizId, finished: Boolean, duration: Long?, sections: List<Section<Any, Any?, String>>) = query()
    {
        update({ table.id eq id})
        {
            it[table.sections] = sections
            it[table.finished] = finished
            it[table.duration] = duration
            it[table.sections] = sections
        }
    }

    suspend fun updateQuizAnswerCorrect(id: QuizId, correct: List<List<Boolean?>>, tokenUsage: AiResponse.Usage)
    {
        val q = getQuiz(id) ?: return
        if (q.sections.size != correct.size) error("Sections size must be equal")
        (q.sections zip correct).forEach {
            if (it.first.questions.size != it.second.size) error("Questions size must be equal")
        }
        query()
        {
            update({ table.id eq id })
            {
                it[table.correct] = correct
                it[table.tokenUsage] = tokenUsage
            }
        }
    }

    suspend fun getQuizzesOrderByTokenUsage(begin: Long, count: Int): Slice<Quiz<Any, Any?, String>> = query()
    {
        selectAll()
            .orderBy(tokenUsage.extract<Long>("total_tokens", toScalar = false) to SortOrder.DESC)
            .asSlice(begin, count)
            .map(::deserialize)
    }

    suspend fun getQuizzesOrderByQuestionCount(begin: Long, count: Int): Slice<Quiz<Any, Any?, String>> = query()
    {
        selectAll()
            .orderBy(CustomFunction("jsonb_array_length", LongColumnType(), table.sections) to SortOrder.DESC)
            .asSlice(begin, count)
            .map(::deserialize)
    }
}