package moe.tachyon.quiz.database

import moe.tachyon.quiz.database.Records.RecordTable
import moe.tachyon.quiz.plugin.contentNegotiation.dataJson
import moe.tachyon.quiz.utils.ai.AiRequest
import moe.tachyon.quiz.utils.ai.DefaultAiResponse
import moe.tachyon.quiz.utils.ai.StreamAiResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.json.jsonb

class Records: SqlDao<RecordTable>(RecordTable)
{
    @Serializable
    sealed interface ResponseRecord
    {
        @Serializable
        data class DefaultResponseRecord(val response: DefaultAiResponse): ResponseRecord
        {
            val type: String = "default"
        }
        @Serializable
        data class StreamResponseRecord(val response: List<StreamAiResponse>): ResponseRecord
        {
            val type: String = "stream"
        }
    }

    object RecordTable: IdTable<Long>("records")
    {
        override val id = long("id").autoIncrement().entityId()
        val url = text("url").nullable().default(null)
        val request = jsonb<AiRequest>("request", dataJson, dataJson.serializersModule.serializer())
        val response = jsonb<ResponseRecord>("response", dataJson, dataJson.serializersModule.serializer()).nullable()
        override val primaryKey = PrimaryKey(id)
    }

    suspend fun addRecord(
        url: String,
        request: AiRequest,
        response: DefaultAiResponse?,
    ) = query()
    {
        insertAndGetId {
            it[RecordTable.url] = url
            it[RecordTable.request] = request
            it[RecordTable.response] = response?.let(ResponseRecord::DefaultResponseRecord)
        }
    }

    suspend fun addRecord(
        url: String,
        request: AiRequest,
        response: List<StreamAiResponse>?,
    ) = query()
    {
        insertAndGetId {
            it[RecordTable.url] = url
            it[RecordTable.request] = request
            it[RecordTable.response] = response?.let(ResponseRecord::StreamResponseRecord)
        }
    }
}