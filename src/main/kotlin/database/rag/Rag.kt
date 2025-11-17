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
        val content = text("content")
        val vector = vector("vector", 4096)

        override val primaryKey = PrimaryKey(id)
    }

    override fun Transaction.init()
    {
        exec("CREATE EXTENSION IF NOT EXISTS vector;")
//        exec("CREATE INDEX IF NOT EXISTS vector_l2 ON rag USING hnsw (vector vector_l2_ops);")
//        exec("CREATE INDEX IF NOT EXISTS vector_ip ON rag USING hnsw (vector vector_ip_ops);")
//        exec("CREATE INDEX IF NOT EXISTS vector_cosine ON rag USING hnsw (vector vector_cosine_ops);")
    }

    suspend fun insert(filePath: String, content: String, vector: List<Double>): Int = query()
    {
        val vec =
            if (vector.size > 4096)
                vector.subList(0, 4096)
            else if (vector.size < 4096)
                vector + List(4096 - vector.size) { 0.0 }
            else
                vector
        vec.size == 4096 || error("Vector size must be 4096")
        insertAndGetId()
        { row ->
            row[this.filePath] = filePath
            row[this.content] = content
            row[this.vector] = vectorParam(vec)
        }.value
    }

    suspend fun query(prefix: String, keyWord: String?, vector: List<Double>, count: Int = 10): List<Pair<String, Double>> = query()
    {
        val expr = (table.vector vectorL2Ops vector).alias("len")
        select(filePath, expr)
            .apply()
            {
                if (prefix.isNotEmpty()) andWhere { filePath like "$prefix%" }
                if (!keyWord.isNullOrEmpty())
                    andWhere { keyWord.split(" ").fold(Op.TRUE as Op<Boolean>) { acc, s -> acc or (filePath like "%$s%") } }
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