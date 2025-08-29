package moe.tachyon.quiz.utils.ai.chat.tools

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.WebSocketClientTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import moe.tachyon.quiz.config.McpConfig.Type.*
import moe.tachyon.quiz.config.mcpConfig
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.Locks
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.ContentNode
import moe.tachyon.quiz.version
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.*
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource.Monotonic.markNow
import kotlin.uuid.ExperimentalUuidApi

object MCP
{
    private val logger = SubQuizLogger.getLogger<MCP>()
    private val client = HttpClient(CIO)
    {
        engine()
        {
            dispatcher = Dispatchers.IO
            requestTimeout = 0
        }
        install(ContentNegotiation)
        {
            json(contentNegotiationJson)
        }
    }

    private val locks = Locks<ChatId>()
    private val refs = Collections.synchronizedMap(mutableMapOf<ChatId, McpClientRef>())
    private val queue = ReferenceQueue<ClientWrapper>()
    private data class ClientWrapper(val value: Client)
    private class McpClientRef(val chat: ChatId, clientWrapper: ClientWrapper): WeakReference<ClientWrapper>(clientWrapper, queue)
    {
        private val client = clientWrapper.value
        suspend fun cleanUp()
        {
            logger.fine("Cleaning up MCP stdio transport for $chat, total clients: ${refs.size}")
            client.close()
            clear()
        }
    }

    init
    {
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch()
        {
            while (true)
            {
                val ref = runCatching { queue.remove() }.getOrNull() ?: continue
                ref as McpClientRef
                locks.withLock(ref.chat)
                {
                    refs.remove(ref.chat)
                    logger.severe("failed to clean up MCP stdio transport")
                    {
                        ref.cleanUp()
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun getClient(chat: ChatId): ClientWrapper = locks.withLock(chat)
    {
        refs[chat]?.get()?.let { return@withLock it }
        val client = ClientWrapper(Client(clientInfo = Implementation(name = "SubQuizMcpClient-$chat", version = version)))
        refs[chat] = McpClientRef(chat, client)
        logger.fine("Creating MCP client for chat($chat): ${client.value}, total clients: ${refs.size}")
        mcpConfig.mcp.filter { it.enable }.forEach()
        {
            val mark = markNow()
            val transport = when(it.type)
            {
                SSE       -> SseClientTransport(MCP.client, it.path)
                WEBSOCKET -> WebSocketClientTransport(MCP.client, it.path)
                STDIO     ->
                {
                    val process = ProcessBuilder(it.path, *it.args.toTypedArray())
                        .redirectErrorStream(true)
                        .apply()
                        {
                            environment().putAll(it.env)
                        }.start()
                    StdioClientTransport(process.inputStream.asSource().buffered(), process.outputStream.asSink().buffered())
                }
            }
            client.value.connect(transport)
            val time = mark.elapsedNow()
            if (time > 10.seconds)
            {
                logger.warning("connect to transport took too long: $time, chat: $chat $it")
            }
        }
        return@withLock client
    }

    init
    {
        AiTools.registerTool()
        { chat, _ ->
            if (mcpConfig.mcp.isEmpty()) return@registerTool emptyList()
            val client: ClientWrapper = getClient(chat.id)
            val tools = client.value.listTools()?.tools ?: return@registerTool emptyList()
            tools.map()
            { t ->
                val schema = AiTools.aiNegotiationJson.encodeToJsonElement(t.inputSchema).jsonObject
                val description = t.description ?: ""
                val name = t.name
                val displayName = t.annotations?.title ?: name
                AiToolInfo<JsonObject>(
                    name = name,
                    displayName = displayName,
                    description = description,
                    dataSchema = JsonSchema.UnknownJsonSchema(schema),
                    type = typeOf<JsonObject>(),
                    invoke = { parms ->
                        val res = client.value.callTool(CallToolRequest(name, parms))
                        val content = res?.content?.mapNotNull()
                        { c ->
                            when (c)
                            {
                                is ImageContent     -> c.data.let(ContentNode::image)
                                is TextContent      -> c.text?.let(ContentNode::text)
                                is EmbeddedResource,
                                is AudioContent,
                                is UnknownContent   ->
                                {
                                    logger.warning("MCP工具返回了不支持的内容类型: ${c})")
                                    null
                                }
                            }
                        }?.takeIf(List<*>::isNotEmpty) ?: listOf(ContentNode("该工具没有返回内容"))

                        AiToolInfo.ToolResult(Content(content))
                    }
                )
            }
        }
    }
}