package cn.org.subit.database

import cn.org.subit.config.systemConfig
import cn.org.subit.dataClass.*
import cn.org.subit.dataClass.Slice
import cn.org.subit.database.utils.asSlice
import cn.org.subit.database.utils.singleOrNull
import cn.org.subit.plugin.contentNegotiation.dataJson
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
        )
        val description = text("description")
        val questions = jsonb<List<Question<Int, Nothing?, String>>>("questions", dataJson)
        override val primaryKey = PrimaryKey(id)
    }

    private val sectionTypeTable by lazy { get<SectionTypes>().table }

    private fun deserialize(row: ResultRow): Section<Int, Nothing?, String> = SectionTable.run()
    {
        Section(
            id = row[id].value,
            subject = row[sectionTypeTable.subject].value,
            type = row[type].value,
            description = row[description],
            questions = row[questions]
        )
    }

    suspend fun getSection(id: SectionId): Section<Int, Nothing?, String>? = query()
    {
        table.join(sectionTypeTable, JoinType.INNER, table.type, sectionTypeTable.id)
            .select(table.columns + sectionTypeTable.subject)
            .where { SectionTable.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun recommendSections(
        user: UserId,
        subject: SubjectId?,
        count: Int
    ): Slice<Section<Int, Nothing?, String>> = query()
    {
        val preferencesTable = get<Preferences>().table
        val historyTable = get<Histories>().table
        table
            .join(sectionTypeTable, JoinType.INNER, table.type, sectionTypeTable.id)
            .join(preferencesTable, JoinType.LEFT, table.type, preferencesTable.type) { preferencesTable.user eq user }
            .join(
                historyTable,
                JoinType.LEFT,
                table.id,
                historyTable.section
            ) { (historyTable.user eq user) and (historyTable.score greaterEq systemConfig.score) }
            .select(table.columns + sectionTypeTable.subject)
            .apply { subject?.let { andWhere { sectionTypeTable.subject eq it } } }
            .groupBy(*table.columns.toTypedArray())
            .groupBy(*preferencesTable.columns.toTypedArray())
            .groupBy(*sectionTypeTable.columns.toTypedArray())
            .andHaving { historyTable.id.count() eq 0 }
            .orderBy(
                coalesce(preferencesTable.value, longParam(1000)) * CustomFunction("RANDOM", LongColumnType()),
                SortOrder.DESC
            )
            .asSlice(0, count)
            .map(::deserialize)
    }

    suspend fun newSection(
        type: SectionTypeId,
        description: String,
        questions: List<Question<Int, Nothing?, String>>
    ) = query()
    {
        insertAndGetId()
        {
            it[table.type] = type
            it[table.description] = description
            it[table.questions] = questions
        }.value
    }

    suspend fun updateSection(
        id: SectionId,
        type: SectionTypeId?,
        description: String?,
        questions: List<Question<Int, Nothing?, String>>?
    ) = query()
    {
        if (type == null && description == null && questions == null) return@query false
        update({ table.id eq id })
        {
            if (type != null) it[table.type] = type
            if (description != null) it[table.description] = description
            if (questions != null) it[table.questions] = questions
        } > 0
    }

    suspend fun deleteSection(id: SectionId) = query()
    {
        deleteWhere { table.id eq id } > 0
    }

    suspend fun getSections(
        subject: SubjectId?,
        type: SectionTypeId?,
        begin: Long,
        count: Int
    ): Slice<Section<Int, Nothing?, String>> = query()
    {
        table
            .join(sectionTypeTable, JoinType.INNER, table.type, sectionTypeTable.id)
            .select(table.columns + sectionTypeTable.subject)
            .apply { subject?.let { andWhere { sectionTypeTable.subject eq it } } }
            .apply { type?.let { andWhere { table.type eq it } } }
            .asSlice(begin, count)
            .map(::deserialize)
    }
}