package moe.tachyon.quiz.database.rag

import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.SqlDao
import moe.tachyon.quiz.database.Users
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class UserRag: SqlDao<UserRag.RagTable>(RagTable)
{
    object RagTable: IdTable<Int>("user_rag")
    {
        override val id = integer("id").autoIncrement().entityId()
        val filePath = text("file_path").index()
        val content = text("content")
        val vector = vector("vector", 4096)
        val user = reference("user", Users.UserTable).index()

        override val primaryKey = PrimaryKey(id)
    }

    override fun Transaction.init()
    {
        exec("CREATE EXTENSION IF NOT EXISTS vector;")
//        exec("CREATE INDEX IF NOT EXISTS vector_l2 ON rag USING ivfflat (vector vector_l2_ops);")
//        exec("CREATE INDEX IF NOT EXISTS vector_ip ON rag USING ivfflat (vector vector_ip_ops);")
//        exec("CREATE INDEX IF NOT EXISTS vector_cosine ON rag USING ivfflat (vector vector_cosine_ops);")
    }

    suspend fun insert(user: UserId, filePath: String, content: String, vector: List<Double>): Int = query()
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
            row[table.filePath] = filePath
            row[table.vector] = vectorParam(vec)
            row[table.content] = content
            row[table.user] = user
        }.value
    }

    suspend fun query(user: UserId, prefix: String, vector: List<Double>, count: Int = 10): List<Pair<String, Double>> = query()
    {
        val expr = (table.vector vectorL2Ops vector).alias("len")
        select(filePath, expr)
            .where { table.user eq user }
            .apply()
            {
                if (prefix.isNotEmpty()) andWhere { filePath like "$prefix%" }
            }
            .orderBy(expr.aliasOnlyExpression())
            .limit(count)
            .map { it[filePath] to it[expr] }
    }

    suspend fun remove(user: UserId, filePath: String): Int = query()
    {
        deleteWhere { (table.filePath eq filePath) and (table.user eq user) }
    }

    suspend fun removeAll(user: UserId) = query()
    {
        deleteWhere { table.user eq user }
    }

    suspend fun getAllFiles(user: UserId): Set<String> = query()
    {
        select(filePath).where { table.user eq user }.map { it[filePath] }.toSet()
    }
}