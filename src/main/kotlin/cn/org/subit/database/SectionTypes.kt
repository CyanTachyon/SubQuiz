package cn.org.subit.database

import cn.org.subit.dataClass.SectionType
import cn.org.subit.dataClass.SectionTypeId
import cn.org.subit.dataClass.KnowledgePointId
import cn.org.subit.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class SectionTypes: SqlDao<SectionTypes.SectionTypeTable>(SectionTypeTable)
{
    object SectionTypeTable: IdTable<SectionTypeId>("sectionTypes")
    {
        override val id = sectionTypeId("id").autoIncrement().entityId()
        val knowledgePoint = reference("knowledgePoint", KnowledgePoints.KnowledgePointTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val name = text("name").index()
        override val primaryKey = PrimaryKey(id)

        init
        {
            uniqueIndex(knowledgePoint, name)
        }
    }

    private fun deserialize(row: ResultRow) = SectionTypeTable.run()
    {
        SectionType(
            row[id].value,
            row[knowledgePoint].value,
            row[name],
        )
    }

    suspend fun getSectionType(id: SectionTypeId) = query()
    {
        selectAll()
            .andWhere { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun newSectionType(knowledgePoint: KnowledgePointId, name: String): SectionTypeId? = query()
    {
        insertIgnoreAndGetId()
        {
            it[table.knowledgePoint] = knowledgePoint
            it[table.name] = name
        }?.value
    }

    suspend fun updateSectionType(id: SectionTypeId, knowledgePoint: KnowledgePointId, name: String): Boolean = query()
    {
        update({ table.id eq id })
        {
            it[table.knowledgePoint] = knowledgePoint
            it[table.name] = name
        } > 0
    }

    suspend fun deleteSectionType(id: SectionTypeId): Boolean = query()
    {
        deleteWhere { table.id eq id } > 0
    }

    suspend fun getSectionTypes(knowledgePoint: KnowledgePointId?): List<SectionType> = query()
    {
        selectAll()
            .apply { knowledgePoint?.let { andWhere { table.knowledgePoint eq it } } }
            .map(::deserialize)
    }
}