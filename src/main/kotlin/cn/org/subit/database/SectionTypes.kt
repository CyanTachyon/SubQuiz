package cn.org.subit.database

import cn.org.subit.dataClass.SectionType
import cn.org.subit.dataClass.SectionTypeId
import cn.org.subit.dataClass.Slice
import cn.org.subit.dataClass.SubjectId
import cn.org.subit.database.utils.asSlice
import cn.org.subit.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class SectionTypes: SqlDao<SectionTypes.SectionTypeTable>(SectionTypeTable)
{
    object SectionTypeTable: IdTable<SectionTypeId>("sectionTypes")
    {
        override val id = sectionTypeId("id").autoIncrement().entityId()
        val subject = reference("subject", Subjects.SubjectTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val name = text("name").index()
        val description = text("description")
        override val primaryKey = PrimaryKey(id)

        init
        {
            uniqueIndex(subject, name)
        }
    }

    private fun deserialize(row: ResultRow) = SectionTypeTable.run()
    {
        SectionType(
            row[id].value,
            row[subject].value,
            row[name],
            row[description],
        )
    }

    suspend fun getSectionType(id: SectionTypeId) = query()
    {
        selectAll()
            .andWhere { table.id eq id }
            .singleOrNull()
            ?.let(::deserialize)
    }

    suspend fun newSectionType(subject: SubjectId, name: String, description: String): SectionTypeId? = query()
    {
        insertIgnoreAndGetId()
        {
            it[table.subject] = subject
            it[table.name] = name
            it[table.description] = description
        }?.value
    }

    suspend fun updateSectionType(id: SectionTypeId, subject: SubjectId, name: String, description: String): Boolean = query()
    {
        update({ table.id eq id })
        {
            it[table.subject] = subject
            it[table.name] = name
            it[table.description] = description
        } > 0
    }

    suspend fun deleteSectionType(id: SectionTypeId): Boolean = query()
    {
        deleteWhere { table.id eq id } > 0
    }

    suspend fun getSectionTypes(subject: SubjectId?, begin: Long, count: Int): Slice<SectionType> = query()
    {
        selectAll()
            .apply { subject?.let { andWhere { table.subject eq it } } }
            .asSlice(begin, count)
            .map(::deserialize)
    }
}