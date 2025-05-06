package cn.org.subit.database

import cn.org.subit.dataClass.KnowledgePoint
import cn.org.subit.dataClass.KnowledgePointId
import cn.org.subit.dataClass.PreparationGroupId
import cn.org.subit.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class KnowledgePoints: SqlDao<KnowledgePoints.KnowledgePointTable>(KnowledgePointTable)
{
    object KnowledgePointTable: IdTable<KnowledgePointId>("knowledge_points")
    {
        override val id = knowledgePointId("id").autoIncrement().entityId()
        val group = reference("group", PreparationGroups.PreparationGroupTable).index()
        val name = varchar("name", 255).index()
        val folder = bool("folder").default(false)
        val father = reference("father", this).nullable().index()
        override val primaryKey = PrimaryKey(id)

        init
        {
            uniqueIndex(group, father, name)
        }
    }

    private fun deserialize(row: ResultRow) =
        KnowledgePoint(
            id = row[table.id].value,
            group = row[table.group].value,
            name = row[table.name],
            folder = row[table.folder],
            father = row[table.father]?.value,
        )

    suspend fun getKnowledgePoint(id: KnowledgePointId): KnowledgePoint? = query()
    {
        selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun getKnowledgePoints(group: PreparationGroupId) = query()
    {
        selectAll()
            .where { table.group eq group }
            .orderBy(table.id to SortOrder.ASC)
            .toList()
            .map(::deserialize)
    }

    suspend fun addKnowledgePoint(
        group: PreparationGroupId,
        name: String,
        folder: Boolean,
        father: KnowledgePointId?,
    ): KnowledgePointId? = query()
    {
        insertIgnoreAndGetId()
        {
            it[table.group] = group
            it[table.name] = name
            it[table.folder] = folder
            it[table.father] = father
        }?.value
    }

    suspend fun updateKnowledgePoint(
        id: KnowledgePointId,
        name: String,
        folder: Boolean,
        father: KnowledgePointId?,
    ) = query()
    {
        update({ table.id eq id })
        {
            it[table.name] = name
            it[table.folder] = folder
            it[table.father] = father
        } > 0
    }
}