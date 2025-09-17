package moe.tachyon.quiz.database

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import moe.tachyon.quiz.database.Records.RecordTable
import moe.tachyon.quiz.plugin.contentNegotiation.dataJson
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class Records: SqlDao<RecordTable>(RecordTable)
{
    object RecordTable: IdTable<Long>("records")
    {
        override val id = long("id").autoIncrement().entityId()
        val url = text("url").nullable().default(null)
        val request = jsonb<JsonElement>("request", dataJson, dataJson.serializersModule.serializer())
        val response = jsonb<JsonElement>("response", dataJson, dataJson.serializersModule.serializer()).nullable()
        val time = timestamp("time").defaultExpression(CurrentTimestamp)
        override val primaryKey = PrimaryKey(id)
    }

    suspend inline fun <reified T: Any, reified R> addRecord(
        url: String,
        request: T,
        response: R,
    ) = addRecord(
        url,
        dataJson.encodeToJsonElement<T>(request),
        response?.let { dataJson.encodeToJsonElement<R>(it) }
    )

    suspend fun addRecord(
        url: String,
        request: JsonElement,
        response: JsonElement?
    ) = query()
    {
        insertAndGetId()
        {
            it[RecordTable.url] = url
            it[RecordTable.request] = request
            it[RecordTable.response] = response
        }
    }
}