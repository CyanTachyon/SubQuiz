package moe.tachyon.quiz.database

import moe.tachyon.quiz.dataClass.ExamId
import moe.tachyon.quiz.dataClass.SectionId
import moe.tachyon.quiz.dataClass.UserId
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.batchUpsert

class Histories: SqlDao<Histories.HistoryTable>(HistoryTable)
{
    object HistoryTable: CompositeIdTable("histories")
    {
        val user = reference("user", Users.UserTable).index()
        val section = reference("section", Sections.SectionTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val score = double("score").index()
        val exam = reference("exam", Exams.ExamTable, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE).index().nullable()
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
}