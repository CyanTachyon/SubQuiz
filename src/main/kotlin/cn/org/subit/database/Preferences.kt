package cn.org.subit.database

import cn.org.subit.dataClass.KnowledgePointId
import cn.org.subit.dataClass.SectionTypeId
import cn.org.subit.dataClass.UserId
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.upsert
import kotlin.math.roundToLong

class Preferences: SqlDao<Preferences.PreferenceTable>(PreferenceTable)
{
    companion object
    {
        const val DEFAULT_PREFERENCE = 10000L
    }

    object PreferenceTable: CompositeIdTable("preferences")
    {
        val user = reference("user", Users.UsersTable).index()
        val knowledgePoint = reference("knowledge_point", KnowledgePoints.KnowledgePointTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val value = double("value")
        override val primaryKey = PrimaryKey(user, knowledgePoint)

        init
        {
            addIdColumn(user)
            addIdColumn(knowledgePoint)
        }
    }

    suspend fun addPreference(user: UserId, knowledgePoint: KnowledgePointId, score: Double): Unit = query()
    {
        val oldValue = select(value)
            .andWhere { PreferenceTable.user eq user }
            .andWhere { PreferenceTable.knowledgePoint eq knowledgePoint }
            .singleOrNull()
            ?.get(value)
            ?: 0.0

        val newValue = ((1.0 - oldValue) * 0.95 + score * 0.05)
        upsert()
        {
            it[PreferenceTable.user] = user
            it[PreferenceTable.knowledgePoint] = knowledgePoint
            it[value] = 1 - newValue
        }
    }
}