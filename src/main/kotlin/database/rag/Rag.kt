package moe.tachyon.quiz.database.rag

import moe.tachyon.quiz.database.SqlDao
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class Rag: SqlDao<Rag.RagTable>(RagTable)
{
    object RagTable: IdTable<Int>("rag")
    {
        override val id = integer("id").autoIncrement().entityId()
        val filePath = text("file_path").index()
        val vector = vector("vector", 4096)

        override val primaryKey = PrimaryKey(id)
    }

    override fun Transaction.init()
    {
        exec("CREATE EXTENSION IF NOT EXISTS vector;")
//        exec("CREATE INDEX IF NOT EXISTS vector_l2 ON rag USING ivfflat (vector vector_l2_ops);")
//        exec("CREATE INDEX IF NOT EXISTS vector_ip ON rag USING ivfflat (vector vector_ip_ops);")
//        exec("CREATE INDEX IF NOT EXISTS vector_cosine ON rag USING ivfflat (vector vector_cosine_ops);")
    }

    suspend fun insert(filePath: String, vector: List<Double>): Int = query()
    {
        require(vector.size == 4096) { "Vector must have exactly 4096 dimensions, but got ${vector.size}" }
        insertAndGetId()
        { row ->
            row[this.filePath] = filePath
            row[this.vector] = vectorParam(vector)
        }.value
    }

    suspend fun query(prefix: String, vector: List<Double>, count: Int = 10): List<Pair<String, Double>> = query()
    {
        val expr = (table.vector vectorL2Ops vector).alias("len")
        select(filePath, expr)
            .apply()
            {
                if (prefix.isNotEmpty()) andWhere { filePath like "$prefix%" }
            }
            .orderBy(expr.aliasOnlyExpression())
            .limit(count)
            .map { it[filePath] to it[expr] }
    }

    suspend fun remove(filePath: String): Int = query()
    {
        deleteWhere { this.filePath eq filePath }
    }

    suspend fun removeAll() = query()
    {
        SchemaUtils.drop(table)
        SchemaUtils.create(table)
    }

    suspend fun getAllFiles(): Set<String> = query()
    {
        select(filePath).map { it[filePath] }.toSet()
    }
}