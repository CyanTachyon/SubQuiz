package moe.tachyon.quiz.database

import kotlinx.datetime.Clock
import moe.tachyon.quiz.dataClass.ExamId
import moe.tachyon.quiz.dataClass.SectionId
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.utils.CustomExpressionWithColumnType
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.KotlinInstantColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import kotlin.time.Duration

class Histories: SqlDao<Histories.HistoryTable>(HistoryTable)
{
    object HistoryTable: CompositeIdTable("histories")
    {
        val user = reference("user", Users.UserTable).index()
        val section = reference("section", Sections.SectionTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val score = double("score").index()
        val exam = reference("exam", Exams.ExamTable, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE).index().nullable()
        val time = timestamp("time").index().defaultExpression(CurrentTimestamp)
        override val primaryKey = PrimaryKey(user, section)

        init
        {
            addIdColumn(user)
            addIdColumn(section)
        }
    }

    suspend fun addHistories(user: UserId, sections: Map<SectionId, Double>, exam: ExamId?): Unit = query()
    {
        batchUpsert(sections.entries)
        {
            this[this@Histories.table.user] = user
            this[section] = it.key
            this[score] = it.value
            this[this@Histories.table.exam] = exam
        }
    }

    suspend fun getHistoryCount(user: UserId, duration: Duration): Map<Int, Int> = query()
    {
        val count = CustomExpressionWithColumnType("*", IntegerColumnType()).count().alias("count")
        val day = CustomFunction("DATE_TRUNC", KotlinInstantColumnType(), stringParam("day"), table.time).alias("day0")
        val res = select(count, day)
            .andWhere { table.user eq user }
            .andWhere { table.time greaterEq Clock.System.now() - duration }
            .groupBy(day)
            .orderBy(day to SortOrder.DESC)
            .toList()
        val dayMillis = 24 * 60 * 60 * 1000
        val today = Clock.System.now().toEpochMilliseconds() / dayMillis * dayMillis
        res.associate { ((today - it[day].toEpochMilliseconds()) / dayMillis) to it[count] }
            .filter { it.key < 30 }
            .map { it.key.toInt() to it.value.toInt() }
            .toMap()
    }
}