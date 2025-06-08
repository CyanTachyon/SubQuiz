package cn.org.subit.database

import cn.org.subit.database.Records.RecordTable
import cn.org.subit.plugin.contentNegotiation.dataJson
import cn.org.subit.utils.ai.AiRequest
import cn.org.subit.utils.ai.DefaultAiResponse
import cn.org.subit.utils.ai.StreamAiResponse
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
        val request = jsonb<AiRequest>("request", dataJson, dataJson.serializersModule.serializer())
        val response = jsonb<ResponseRecord>("response", dataJson, dataJson.serializersModule.serializer()).nullable()
        override val primaryKey = PrimaryKey(id)
    }

    suspend fun addRecord(
        request: AiRequest,
        response: DefaultAiResponse?,
    ) = query()
    {
        insertAndGetId {
            it[RecordTable.request] = request
            it[RecordTable.response] = response?.let(ResponseRecord::DefaultResponseRecord)
        }
    }

    suspend fun addRecord(
        request: AiRequest,
        response: List<StreamAiResponse>?,
    ) = query()
    {
        insertAndGetId {
            it[RecordTable.request] = request
            it[RecordTable.response] = response?.let(ResponseRecord::StreamResponseRecord)
        }
    }
}