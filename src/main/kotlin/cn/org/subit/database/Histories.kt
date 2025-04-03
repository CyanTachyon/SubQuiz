package cn.org.subit.database

import cn.org.subit.dataClass.SectionId
import cn.org.subit.dataClass.UserId
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.batchUpsert

class Histories: SqlDao<Histories.HistoryTable>(HistoryTable)
{
    object HistoryTable: CompositeIdTable("histories")
    {
        val user = reference("user", Users.UsersTable).index()
        val section = reference("section", Sections.SectionTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val score = double("score").index()
        override val primaryKey = PrimaryKey(user, section)

        init
        {
            addIdColumn(user)
            addIdColumn(section)
        }
    }

    suspend fun addHistories(user: UserId, sections: Map<SectionId, Double>): Unit = query()
    {
        batchUpsert(sections.entries)
        {
            this[HistoryTable.user] = user
            this[section] = it.key
            this[score] = it.value
        }
    }
}