package cn.org.subit.database

import cn.org.subit.config.systemConfig
import cn.org.subit.dataClass.*
import cn.org.subit.dataClass.Slice
import cn.org.subit.database.utils.asSlice
import cn.org.subit.database.utils.singleOrNull
import cn.org.subit.plugin.contentNegotiation.dataJson
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.coalesce
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.json.jsonb
import org.koin.core.component.get

class Sections: SqlDao<Sections.SectionTable>(SectionTable)
{
    object SectionTable: IdTable<SectionId>("sections")
    {
        override val id = sectionId("id").autoIncrement().entityId()
        val type = reference(
            "type",
            SectionTypes.SectionTypeTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val description = text("description")
        val weight = integer("weight").default(50)
        val available = bool("available").default(true)
        val markdown = bool("markdown").default(false)
        val questions = jsonb<List<Question<Any, Nothing?, String>>>("questions", dataJson, dataJson.serializersModule.serializer())
        override val primaryKey = PrimaryKey(id)
    }

    private val sectionTypeTable by lazy { get<SectionTypes>().table }

    private fun deserialize(row: ResultRow): Section<Any, Nothing?, String> = SectionTable.run()
    {
        Section(
            id = row[id].value,
            type = row[type].value,
            description = row[description],
            weight = row[weight],
            available = row[available],
            markdown = row[markdown],
            questions = row[questions]
        )
    }

    suspend fun getSection(id: SectionId): Section<Any, Nothing?, String>? = query()
    {
        table
            .select(table.columns)
            .where { SectionTable.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun recommendSections(
        user: UserId,
        knowledgePoints: List<KnowledgePointId>?,
        count: Int
    ): Slice<Section<Any, Nothing?, String>> = query()
    {
        val preferencesTable = get<Preferences>().table
        val historyTable = get<Histories>().table
        table
            .join(sectionTypeTable, JoinType.INNER, table.type, sectionTypeTable.id)
            .join(preferencesTable, JoinType.LEFT, sectionTypeTable.knowledgePoint, preferencesTable.knowledgePoint) { preferencesTable.user eq user }
            .join(
                historyTable,
                JoinType.LEFT,
                table.id,
                historyTable.section
            ) { (historyTable.user eq user) and (historyTable.score greaterEq systemConfig.score) }
            .select(table.columns)
            .apply { knowledgePoints?.let { andWhere { sectionTypeTable.knowledgePoint inList it } } }
            .andWhere { available eq true }
            .groupBy(*table.columns.toTypedArray())
            .groupBy(*preferencesTable.columns.toTypedArray())
            .groupBy(*sectionTypeTable.columns.toTypedArray())
            .andHaving { historyTable.user.count() eq 0 }
            .orderBy(
                coalesce(preferencesTable.value, longParam(Preferences.DEFAULT_PREFERENCE)) *
                table.weight *
                CustomFunction("RANDOM", LongColumnType()),
                SortOrder.DESC
            )
            .asSlice(0, count)
            .map(::deserialize)
    }

    suspend fun newSection(section: Section<Any, Nothing?, String>) = query()
    {
        insertAndGetId()
        {
            it[table.type] = section.type
            it[table.description] = section.description
            it[table.weight] = section.weight
            it[table.available] = section.available
            it[table.markdown] = section.markdown
            it[table.questions] = section.questions
        }.value
    }

    suspend fun updateSection(section: Section<Any, Nothing?, String>) = query()
    {
        update({ table.id eq section.id })
        {
            it[table.type] = section.type
            it[table.description] = section.description
            it[table.weight] = section.weight
            it[table.available] = section.available
            it[table.markdown] = section.markdown
            it[table.questions] = section.questions
        } > 0
    }

    suspend fun deleteSection(id: SectionId) = query()
    {
        deleteWhere { table.id eq id } > 0
    }

    suspend fun getSections(
        knowledgePoint: KnowledgePointId,
        sectionType: SectionTypeId?,
        begin: Long,
        count: Int
    ): Slice<Section<Any, Nothing?, String>> = query()
    {
        table
            .join(sectionTypeTable, JoinType.INNER, table.type, sectionTypeTable.id)
            .select(table.columns + sectionTypeTable.knowledgePoint)
            .andWhere { sectionTypeTable.knowledgePoint eq knowledgePoint }
            .apply { sectionType?.let { andWhere { table.type eq it } } }
            .orderBy(id to SortOrder.ASC)
            .asSlice(begin, count)
            .map(::deserialize)
    }
}