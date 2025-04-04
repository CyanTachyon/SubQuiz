package cn.org.subit.database

import cn.org.subit.database.Quizzes.QuizTable
import cn.org.subit.database.Records.RecordTable
import cn.org.subit.plugin.contentNegotiation.dataJson
import cn.org.subit.utils.ai.AiRequest
import cn.org.subit.utils.ai.AiResponse
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.json.jsonb

class Records: SqlDao<RecordTable>(RecordTable)
{
    object RecordTable: IdTable<Long>("records")
    {
        override val id = long("id").autoIncrement().entityId()
        val request = jsonb<AiRequest>("request", dataJson, dataJson.serializersModule.serializer())
        val response = jsonb<AiResponse>("response", dataJson, dataJson.serializersModule.serializer()).nullable()
        override val primaryKey = PrimaryKey(id)
    }

    suspend fun addRecord(
        request: AiRequest,
        response: AiResponse?,
    ) = query()
    {
        insertAndGetId {
            it[RecordTable.request] = request
            it[RecordTable.response] = response
        }
    }
}