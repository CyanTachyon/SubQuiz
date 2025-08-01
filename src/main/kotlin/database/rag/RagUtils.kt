package moe.tachyon.quiz.database.rag

import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import org.jetbrains.exposed.sql.*

class VectorSqlType(
    val dimension: Int
): ColumnType<List<Double>>()
{
    override fun sqlType(): String = "vector($dimension)"

    override fun valueFromDB(value: Any): List<Double>?
    {
        return when (value)
        {
            is List<*> -> value.filterIsInstance<Double>()
            is Array<*> -> value.filterIsInstance<Double>()
            is String -> value
                .trimStart('[')
                .trimEnd(']')
                .split(',')
                .map(String::trim)
                .map(String::toDouble)

            else -> null
        }.takeIf { it?.size == dimension }
    }

    override fun notNullValueToDB(value: List<Double>): Any
    {
        require(value.size == dimension) { "Vector must have exactly $dimension dimensions, but got ${value.size}" }
        // 转为字符串形式，但注意不要出现科学计数法
        return value.joinToString(prefix = "[", postfix = "]")
        {
            contentNegotiationJson.encodeToString(it)
        }
    }
}

fun Table.vector(name: String, dimension: Int): Column<List<Double>> =
    registerColumn(name, VectorSqlType(dimension))

infix fun Column<List<Double>>.vectorL2Ops(other: List<Double>): CustomOperator<Double> =
    CustomOperator(
        "<->",
        DoubleColumnType(),
        this,
        vectorParam(other)
    )

fun vectorParam(
    vector: List<Double>,
    dimension: Int = vector.size
) = CustomFunction(
    "vector",
    VectorSqlType(dimension),
    QueryParameter(
        vector,
        VectorSqlType(dimension)
    )
)