@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.ai

import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonElement
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.ChatId.Companion.toChatIdOrNull
import moe.tachyon.quiz.database.Chats
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.QuestionAnswerSerializer
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.ai.ask.AskService
import moe.tachyon.quiz.utils.ai.chatUtils.AiChatsUtils
import moe.tachyon.quiz.utils.ai.tools.AiTools
import moe.tachyon.quiz.utils.statuses
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = SubQuizLogger.getLogger()
fun Route.ai() = route("/ai", {
    tags("ai")
})
{
    route("/chat")
    {
        post({
            description = "创建AI聊天"
            request()
            {
                body<CreateChatRequest>()
                {
                    required = true
                }
            }
            response()
            {
                statuses<ChatResponse>(HttpStatus.OK, example = ChatResponse.example)
                statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
            }
        }, Context::newChat)

        put( {
            description = "发送AI聊天消息"
            request()
            {
                body<SendChatMessageRequest>()
                {
                    required = true
                    description = "AI聊天内容"
                }
            }
            response()
            {
                statuses<String>(HttpStatus.OK, example = "new-hash")
                statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest, HttpStatus.Conflict)
            }
        }, Context::sendChatMessage)

        get("/models", {
            description = "获取AI聊天模型列表"
            response()
            {
                statuses<List<ChatModelResponse>>(HttpStatus.OK)
                statuses(HttpStatus.Unauthorized)
            }
        }, Context::getChatModels)

        get({
            description = "获取AI聊天列表"
            request()
            {
                paged()
            }
            response()
            {
                statuses<Slice<ChatResponse>>(HttpStatus.OK, example = sliceOf(ChatResponse.example))
                statuses(HttpStatus.Unauthorized)
            }
        }, Context::getChatList)

        route("/{id}", {
            request()
            {
                pathParameter<ChatId>("id")
                {
                    required = true
                    description = "聊天id"
                }
            }
        })
        {
            get({
                description = "获取一个AI聊天"
                response()
                {
                    statuses<ChatResponse>(HttpStatus.OK, example = ChatResponse.example)
                    statuses(HttpStatus.Unauthorized, HttpStatus.NotFound)
                }
            }, Context::getChat)

            delete({
                description = "删除一个AI聊天"
            }, Context::deleteChat)

            post("/cancel",Context::cancelChat)

            route("/sse", HttpMethod.Get,{
                description = "获得AI回复"
                request {
                    queryParameter<String>("hash")
                    {
                        required = true
                    }
                }
                response()
                {
                    statuses<ChatSseMessage>(HttpStatus.OK, example = ChatSseMessage(Content("content"), "reasoning", ""))
                    statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
                }
            })
            {
                plugin(SSE)
                handle(Context::listenChat)
            }

            get("/toolData", {
                request()
                {
                    queryParameter<String>("type")
                    {
                        required = true
                    }
                    queryParameter<String>("path")
                    {
                        required = true
                    }
                }
            }, Context::getToolData)

            route("/file/{file}", {
                request()
                {
                    pathParameter<String>("file")
                    {
                        required = true
                        description = "文件名"
                    }
                }
            })
            {
                get("/info", {
                    request()
                    {
                        queryParameter<Boolean>("download")
                        {
                            required = false
                        }
                    }
                    response()
                    {
                        statuses<ChatFiles.FileInfo>(HttpStatus.OK)
                    }
                }) { getChatFile(true) }

                get("/data") { getChatFile(false) }
            }
        }
    }

    route("/translate", HttpMethod.Post, {
        description = "翻译文本"
        request()
        {
            body<Translate>()
            {
                required = true
                description = "需要翻译的文本"
            }
        }
        response()
        {
            statuses<Text>(HttpStatus.OK, example = Text("翻译后的文本"))
            statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
        }
    })
    {
        plugin(SSE)
        handle(Context::translateText)
    }

    route("/imageToText", HttpMethod.Post, {
        description = "图片转文本"
        request()
        {
            body<ImageToTextRequest>()
            {
                required = true
                description = "图片的base64编码"
            }
        }
        response()
        {
            statuses<Text>(HttpStatus.OK, example = Text("转换后的文本"))
            statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
        }
    })
    {
        plugin(SSE)
        handle(Context::imageToText)
    }
}

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
private val ser = Section.serializer(
    QuestionAnswerSerializer,
    QuestionAnswerSerializer,
    JsonElement.serializer()
)
private class SectionSerializer: KSerializer<Section<Any, Any, JsonElement>> by ser

@Serializable
private data class CreateChatRequest(
    @Serializable(SectionSerializer::class)
    val section: Section<@Contextual Any, @Contextual Any, JsonElement>?,
    val content: String,
    val model: String,
    val images: List<String> = emptyList()
)

private suspend fun Context.newChat(): Nothing
{
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<CreateChatRequest>()
    val chats = get<Chats>()
    val chat = chats.createChat(user.id, body.section)
    val res = logger.warning("Failed to start chat for user ${user.id} with model ${body.model}")
    {
        val content = AiChatsUtils.makeContent(chat.id, body.content, body.images)
        if (content !is AiChatsUtils.MakeContentResult.Success)
            finishCall(HttpStatus.BadRequest.subStatus(content.error))
        val service = AskService.getService(body.model) ?: finishCall(HttpStatus.BadRequest.subStatus("model not found"))
        val hash = AiChatsUtils.startRespond(content.content, chat, service) ?: finishCall(HttpStatus.Conflict)
        ChatResponse(chat.copy(hash = hash))
    }.onFailure { AiChatsUtils.deleteChat(chat.id, user.id) }.getOrThrow()
    finishCall(HttpStatus.OK, res)
}

@Serializable
private data class SendChatMessageRequest(
    val chatId: ChatId,
    val content: String,
    val model: String,
    val hash: String,
    val images: List<String> = emptyList(),
    val regenerate: Boolean = false,
)

private suspend fun Context.sendChatMessage()
{
    val loginUser = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<SendChatMessageRequest>()
    val chats = get<Chats>()
    val chat = chats.getChat(body.chatId)?.let()
    {
        if (body.regenerate) it.copy(histories = it.histories.subList(0, it.histories.indexOfLast { c -> c.role == Role.USER }))
        else it
    } ?: finishCall(HttpStatus.NotFound)
    if (chat.user != loginUser.id) finishCall(HttpStatus.Forbidden)
    if (chat.hash != body.hash) finishCall(HttpStatus.Conflict)
    if (chat.banned) finishCall(HttpStatus.Forbidden.copy(message = AiChatsUtils.BANNED_MESSAGE))
    val service = AskService.getService(body.model) ?: finishCall(HttpStatus.BadRequest.subStatus("model not found"))
    val content = AiChatsUtils.makeContent(chat.id, body.content, body.images)
    if (content !is AiChatsUtils.MakeContentResult.Success)
        finishCall(HttpStatus.BadRequest.subStatus(content.error))
    val hash = AiChatsUtils.startRespond(content.content, chat, service) ?: finishCall(HttpStatus.Conflict)
    finishCall(HttpStatus.OK, hash)
}

@Serializable
private data class ChatModelResponse(
    val model: String,
    val displayName: String,
    val toolable: Boolean,
)

private fun Context.getChatModels()
{
    call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    finishCall(HttpStatus.OK, aiConfig.chats.map { ChatModelResponse(it.model, it.displayName, aiConfig.models[it.model]!!.toolable) })
}

private fun Context.sseHeaders()
{
    call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header(HttpHeaders.Connection, "keep-alive")
    call.response.header("X-Accel-Buffering", "no")
}

@Serializable
private data class ChatSseMessage(
    val content: Content,
    @SerialName("reasoning_content")
    val reasoningContent: String,
    @SerialName("tool_call")
    val toolCall: String,
    val type: AiTools.ToolData.Type? = null
)

@Serializable
private data class ChatResponse(
    val id: ChatId,
    val user: UserId,
    val title: String,
    @Serializable(SectionSerializer::class)
    val section: Section<@Contextual Any, @Contextual Any, JsonElement>?,
    val histories: List<History>,
    val hash: String,
    val banned: Boolean,
)
{
    @Serializable
    data class History(
        val role: Role,
        val messages: List<ChatSseMessage>,
    )

    companion object
    {
        @OptIn(InternalSerializationApi::class)
        private val ser = Section.serializer(
            QuestionAnswerSerializer,
            QuestionAnswerSerializer,
            JsonElement.serializer()
        )
        private class SectionSerializer: KSerializer<Section<Any, Any, JsonElement>> by ser

        val example = ChatResponse(
            id = ChatId(1),
            user = UserId(1),
            title = "示例聊天",
            section = Section.example,
            histories = emptyList(),
            hash = "example-hash",
            banned = false,
        )

        suspend operator fun invoke(chat: Chat): ChatResponse
        {
            if (chat.histories.isEmpty()) return ChatResponse(
                id = chat.id,
                user = chat.user,
                title = chat.title,
                section = chat.section,
                histories = emptyList(),
                hash = chat.hash,
                banned = chat.banned,
            )

            val tools = AiTools.getTools(chat, null)
            val h = chat.histories.mapNotNull()
            { msg ->
                if (msg.role == Role.TOOL)
                    return@mapNotNull null
                if (msg.role == Role.USER)
                    return@mapNotNull listOf(History(Role.USER, listOf(ChatSseMessage(msg.content, "", ""))))
                if (msg.showingType != null)
                    return@mapNotNull listOf(History(Role.ASSISTANT, listOf(ChatSseMessage(msg.content, "", "", msg.showingType))))
                val rc = msg.reasoningContent
                val c  = msg.content.toText()
                val calls = msg.toolCalls.mapNotNull calls@
                { tc ->
                    val tool = tools.firstOrNull { it.name == tc.name } ?: return@calls run()
                    {
                        logger.warning("Tool ${tc.name} not found in chat ${chat.id}, user ${chat.user}")
                        null
                    }
                    tool.displayName
                }.map()
                { toolName ->
                    History(
                        Role.ASSISTANT,
                        listOf(ChatSseMessage(Content(""), "",toolName))
                    )
                }
                listOf(History(Role.ASSISTANT, listOf(ChatSseMessage(Content(c), rc, "")))) + calls
            }
            val list = h.flatten()
            val res = mutableListOf<History>()
            for (msg in list)
            {
                if (res.isEmpty() || res.last().role != msg.role)
                    res.add(msg)
                else
                    res[res.lastIndex] = History(msg.role, res.last().messages + msg.messages)
            }
            return ChatResponse(
                id = chat.id,
                user = chat.user,
                title = chat.title,
                section = chat.section,
                histories = res,
                hash = chat.hash,
                banned = chat.banned,
            )
        }
    }
}

private suspend fun Context.listenChat()
{
    val loginUser = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val hash = call.queryParameters["hash"] ?: finishCall(HttpStatus.BadRequest)
    val chatInfo = AiChatsUtils.getChatInfo(chatId) ?: finishCall(HttpStatus.OK)
    if (chatInfo.chat.user != loginUser.id) finishCall(HttpStatus.Forbidden)
    if (chatInfo.chat.hash != hash) finishCall(HttpStatus.Conflict)

    sseHeaders()

    val res = SSEServerContent(call)
    {
        heartbeat()
        chatInfo.putListener()
        {
            when (it)
            {
                is AiChatsUtils.BannedEvent ->
                    send(event = "banned", data = "chat is banned, you can not continue this chat")
                is AiChatsUtils.FinishedEvent ->
                    send(event = "finished", data = "chat finish normally")
                is AiChatsUtils.NameChatEvent ->
                    send(event = "name", data = contentNegotiationJson.encodeToString(it))
                is AiChatsUtils.MessageEvent ->
                {
                    val msg =
                        when(it.message)
                        {
                            is StreamAiResponseSlice.Message ->
                                ChatSseMessage(Content(it.message.content), it.message.reasoningContent, "")
                            is StreamAiResponseSlice.ToolCall ->
                                if (it.message.tool.displayName != null)
                                    ChatSseMessage(Content(""), "", it.message.tool.displayName)
                                else null
                            is StreamAiResponseSlice.ShowingTool ->
                                ChatSseMessage(Content(it.message.content), "", "", it.message.showingType)
                        }
                    if (msg != null)
                        send(event = "message", data = contentNegotiationJson.encodeToString(msg))
                }
            }
        }
        runCatching { chatInfo.join() }
        send(event = "end", data = "truly end")
    }
    return call.respond(HttpStatusCode.OK, res)
}

private suspend fun Context.getChatList()
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chats = get<Chats>()
    val (begin, count) = call.getPage()
    val chatList = chats.getChats(user.id, begin, count)
    finishCall(HttpStatus.OK, chatList.map { ChatResponse(it) })
}

private suspend fun Context.getChat()
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val chats = get<Chats>()
    val chat = chats.getChat(chatId) ?: finishCall(HttpStatus.NotFound)
    if (chat.user != user.id && !user.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    finishCall(HttpStatus.OK, ChatResponse(chat))
}

private suspend fun Context.deleteChat()
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    if (!AiChatsUtils.deleteChat(chatId, user.id))
        finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.cancelChat()
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val chat = get<Chats>().getChat(chatId) ?: finishCall(HttpStatus.NotFound)
    if (chat.user != user.id) finishCall(HttpStatus.Forbidden)
    AiChatsUtils.cancelChat(chatId)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.getToolData()
{
    val type = call.queryParameters["type"] ?: finishCall(HttpStatus.BadRequest.subStatus("type is required"))
    val path = call.queryParameters["path"] ?: finishCall(HttpStatus.BadRequest.subStatus("path is required"))
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("chat id is required"))
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chat = get<Chats>().getChat(chatId) ?: finishCall(HttpStatus.NotFound)
    if (chat.user != user.id) finishCall(HttpStatus.NotFound)
    val data = AiTools.getData(chat, type, path)
    finishCall(HttpStatus.OK, data ?: AiTools.ToolData(AiTools.ToolData.Type.TEXT, "资源不存在或无法访问"))
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun Context.getChatFile(info: Boolean)
{
    val fileUuid = call.parameters["file"] ?: finishCall(HttpStatus.BadRequest.subStatus("file is required"))
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("chat id is required"))
    val download = call.request.queryParameters["download"]?.toBooleanStrictOrNull() ?: false
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chat = get<Chats>().getChat(chatId) ?: finishCall(HttpStatus.NotFound)
    if (chat.user != user.id) finishCall(HttpStatus.NotFound)
    val file = ChatFiles.getChatFile(chat.id, Uuid.parseHex(fileUuid)) ?: finishCall(HttpStatus.NotFound)
    if (info) finishCall(HttpStatus.OK, file.first)
    else
    {
        if (download)
        {
            val ext = file.first.name.substringAfterLast(".").encodeURLParameter()
            val filename = file.first.name.encodeURLParameter()
            val simpleName = "data" + if (ext.isNotEmpty()) ".$ext" else ""
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"$simpleName\"; filename*=UTF-8''$filename"
            )
        }
        finishCallWithBytes(HttpStatus.OK, ContentType.fromFileExtension(file.first.name).firstOrNull() ?: ContentType.Application.OctetStream, file.second)
    }
}

@Serializable
private data class Text(val text: String)
@Serializable
private data class Translate(
    val text: String,
    val lang0: String = "中文",
    val lang1: String = "英文",
    val twoWay: Boolean = true,
)
private suspend fun Context.translateText()
{
    call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<Translate>()

    sseHeaders()

    val res = SSEServerContent(call)
    {
        heartbeat()
        logger.warning("error in translate")
        {
            AiTranslate.translate(body.text, body.lang0, body.lang1, body.twoWay)
            {
                send(event = "message", data = contentNegotiationJson.encodeToString(Text(it)))
            }
        }
    }
    return call.respond(HttpStatusCode.OK, res)
}

@Serializable
data class ImageToTextRequest(
    val markdown: Boolean,
    val image: String,
)

private suspend fun Context.imageToText()
{
    call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<ImageToTextRequest>()

    sseHeaders()

    val res = SSEServerContent(call)
    {
        heartbeat()
        logger.warning("error in image to text")
        {
            if (body.markdown)
                AiImage.imageToMarkdown(body.image)
                {
                    send(event = "message", data = contentNegotiationJson.encodeToString(Text(it)))
                }
            else
                AiImage.imageToText(body.image)
                {
                    send(event = "message", data = contentNegotiationJson.encodeToString(Text(it)))
                }
        }
    }
    return call.respond(HttpStatusCode.OK, res)
}