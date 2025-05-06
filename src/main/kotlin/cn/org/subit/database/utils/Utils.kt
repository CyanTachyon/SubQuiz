@file:Suppress("unused")

package cn.org.subit.database.utils

import cn.org.subit.dataClass.Slice
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.rowNumber

fun Query.asSlice(begin: Long, limit: Int): Slice<ResultRow>
{
    val totalSize = longParam(0).alias("_Slice_TotalSize_")
    val rowNumber = rowNumber().over().orderBy(*this.orderByExpressions.toTypedArray()).alias("_Slice_RowNumber_")
    val isData = booleanParam(true).alias("_Slice_IsData_")

    val query = this.copy()
    query.set = Slice(
        this.set.source,
        this.set.fields + rowNumber + totalSize
    )
    (query.orderByExpressions as MutableList).clear()
    val q = query.alias("_Slice_Query_")

    val q1 = q.aliasOnly()
        .select(query.set.fields + isData, listOf(TheAny, isData))
        .andWhere { rowNumber.aliasOnlyExpression() greaterEq longParam(begin + 1) }
        .andWhere { rowNumber.aliasOnlyExpression() lessEq longParam(begin + limit) }
    val q2 = q.aliasOnly().select(this.set.fields.map { Null } + Null + longParam(1).withColumnType(LongColumnType()).count() + booleanParam(false))

    val resQ = q1.union(q2)
    val list = WithQuery(resQ, q)
        .apply { prepareSQL(QueryBuilder(false)).let(Slice.logger::finest) }
        .toList()

    val resCount = list.first { !it[isData] }[totalSize]
    val resList = list.filter { it[isData] }.sortedBy { it[rowNumber] }
    return Slice(resCount, begin, resList)
}

fun Query.single() = this.limit(1).toList().first()
fun Query.singleOrNull() = this.limit(1).toList().firstOrNull()

private object TheAny: Expression<Any>()
{
    override fun toQueryBuilder(queryBuilder: QueryBuilder)
    {
        queryBuilder.append("*")
    }
}

private object Null: Expression<Any?>()
{
    override fun toQueryBuilder(queryBuilder: QueryBuilder)
    {
        queryBuilder.append("NULL")
    }
}

/**
 * 选择[columns]列, 但在实际生成的sql中使用[realColumns]列
 */
private fun ColumnSet.select(columns: List<Expression<*>>, realColumns: List<Expression<*>>): Query = object: Query(
    Slice(this@select, columns),
    null
)
{
    override val queryToExecute: Query
        get() = Query(Slice(this@select, columns), this.where)
            .also { this.copyTo(it) }
            .adjustSelect { select(realColumns) }

    override fun prepareSQL(builder: QueryBuilder): String = queryToExecute.prepareSQL(builder)
    override fun prepareSQL(transaction: Transaction, prepared: Boolean): String = prepareSQL(QueryBuilder(prepared))
    override fun copy(): Query = this@select
        .select(columns, realColumns)
        .also {
            this.copyTo(it)
            val where = this.where ?: return@also
            it.where { where }
        }
}

class WithQuery(private val query: AbstractQuery<*>, private vararg val with: QueryAlias): Query(
    query.set,
    null
)
{
    override fun prepareSQL(builder: QueryBuilder): String
    {
        builder()
        {
            with.forEach()
            {
                append("WITH ")
                append(it.alias)
                append(" AS (")
                it.query.prepareSQL(this)
                append(") ")
            }
            query.prepareSQL(this)
        }
        return builder.toString()
    }
}

private fun QueryAlias.aliasOnly() = object: Table(this.alias){}

class CustomExpressionWithColumnType<T>(
    val expression: Expression<T>,
    override val columnType: IColumnType<T & Any>,
): ExpressionWithColumnType<T>()
{
    override fun equals(other: Any?): Boolean
    {
        if (other is CustomExpressionWithColumnType<*>) return this.expression == other.expression
        return this.expression == other
    }

    override fun hashCode(): Int = expression.hashCode()
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = expression.toQueryBuilder(queryBuilder)
    override fun toString(): String = expression.toString()
}

fun <T>Expression<T>.withColumnType(columnType: ColumnType<T & Any>): ExpressionWithColumnType<T> =
    CustomExpressionWithColumnType(this, columnType)
