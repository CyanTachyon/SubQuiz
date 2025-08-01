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
import kotlinx.serialization.builtins.serializer
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.ChatId.Companion.toChatIdOrNull
import moe.tachyon.quiz.database.Chats
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.QuestionAnswerSerializer
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.ai.AiImage
import moe.tachyon.quiz.utils.ai.AiTranslate
import moe.tachyon.quiz.utils.ai.Role
import moe.tachyon.quiz.utils.ai.StreamAiResponseSlice
import moe.tachyon.quiz.utils.ai.ask.AskService
import moe.tachyon.quiz.utils.ai.chatUtils.AiChatsUtils
import moe.tachyon.quiz.utils.ai.tools.AiTools
import moe.tachyon.quiz.utils.statuses

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

        get("/{id}", {
            description = "获取一个AI聊天"
            request {
                pathParameter<ChatId>("id")
                {
                    required = true
                    description = "聊天id"
                }
            }
            response {
                statuses<ChatResponse>(HttpStatus.OK, example = ChatResponse.example)
                statuses(HttpStatus.Unauthorized, HttpStatus.NotFound)
            }
        }, Context::getChat)

        delete("/{id}", {
            description = "删除一个AI聊天"
            request {
                pathParameter<ChatId>("id")
                {
                    required = true
                    description = "聊天id"
                }
            }
            response {
                statuses(HttpStatus.OK, example = "删除成功")
                statuses(HttpStatus.Unauthorized, HttpStatus.NotFound)
            }
        }, Context::deleteChat)

        post("/{id}/cancel", {
            request()
            {
                pathParameter<ChatId>("id")
                {
                    required = true
                    description = "聊天id"
                }
            }
        }, Context::cancelChat)

        route("/sse", HttpMethod.Get,{
            description = "获得AI回复"
            request {
                queryParameter<ChatId>("chat")
                {
                    required = true
                    description = "聊天id"
                }
                queryParameter<String>("hash")
                {
                    required = true
                }
            }
            response()
            {
                statuses<ChatSseMessage>(HttpStatus.OK, example = ChatSseMessage("content", "reasoning", ""))
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
    String.serializer()
)
private class SectionSerializer: KSerializer<Section<Any, Any, String>> by ser

@Serializable
private data class CreateChatRequest(
    @Serializable(SectionSerializer::class)
    val section: Section<@Contextual Any, @Contextual Any, String>?,
    val content: String,
    val model: String,
)

private suspend fun Context.newChat(): Nothing
{
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<CreateChatRequest>()
    val chats = get<Chats>()
    val chat = chats.createChat(user.id, body.section)
    val res = logger.warning("Failed to start chat for user ${user.id} with model ${body.model}")
    {
        val service = AskService.getService(body.model) ?: finishCall(HttpStatus.BadRequest, "model not found")
        val hash = AiChatsUtils.startRespond(body.content, chat, service) ?: finishCall(HttpStatus.Conflict)
        ChatResponse(chat.copy(hash = hash))
    }.onFailure { chats.deleteChat(chat.id, user.id) }.getOrThrow()
    finishCall(HttpStatus.OK, res)
}

@Serializable
private data class SendChatMessageRequest(
    val chatId: ChatId,
    val content: String,
    val model: String,
    val hash: String,
)

private suspend fun Context.sendChatMessage()
{
    val loginUser = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<SendChatMessageRequest>()
    val chats = get<Chats>()
    val chat = chats.getChat(body.chatId) ?: finishCall(HttpStatus.NotFound)
    if (chat.user != loginUser.id) finishCall(HttpStatus.Forbidden)
    if (chat.hash != body.hash) finishCall(HttpStatus.Conflict)
    if (chat.banned) finishCall(HttpStatus.Forbidden.copy(message = AiChatsUtils.BANNED_MESSAGE))
    val service = AskService.getService(body.model) ?: finishCall(HttpStatus.BadRequest, "model not found")
    val hash = AiChatsUtils.startRespond(body.content, chat, service) ?: finishCall(HttpStatus.Conflict)
    finishCall(HttpStatus.OK, hash)
}

@Serializable
data class ChatModelResponse(
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
    val content: String,
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
    val section: Section<@Contextual Any, @Contextual Any, String>?,
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
            String.serializer()
        )
        private class SectionSerializer: KSerializer<Section<Any, Any, String>> by ser

        val example = ChatResponse(
            id = ChatId(1),
            user = UserId(1),
            title = "示例聊天",
            section = Section.example,
            histories = emptyList(),
            hash = "example-hash",
            banned = false,
        )

        operator fun invoke(chat: Chat): ChatResponse
        {
            val tools = AiTools.getTools(chat.user)
            val h = chat.histories.mapNotNull()
            { msg ->
                if (msg.role == Role.TOOL)
                    return@mapNotNull null
                if (msg.role == Role.USER)
                    return@mapNotNull listOf(History(Role.USER, listOf(ChatSseMessage(msg.content.toText(), "", ""))))
                if (msg.showingType != null)
                    return@mapNotNull listOf(History(Role.ASSISTANT, listOf(ChatSseMessage(msg.content.toText(), "", "", msg.showingType))))
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
                        listOf(ChatSseMessage("", "",toolName))
                    )
                }
                listOf(History(Role.ASSISTANT, listOf(ChatSseMessage(c, rc, "")))) + calls
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
    val chatId = call.parameters["chat"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val hash = call.parameters["hash"] ?: finishCall(HttpStatus.BadRequest)
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
                                ChatSseMessage(it.message.content, it.message.reasoningContent, "")
                            is StreamAiResponseSlice.ToolCall ->
                                ChatSseMessage("", "", it.message.tool.displayName)
                            is StreamAiResponseSlice.ShowingTool ->
                                ChatSseMessage(it.message.content, "", "", it.message.showingType)
                        }
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
    finishCall(HttpStatus.OK, chatList.map(ChatResponse::invoke))
}

private suspend fun Context.getChat()
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val chats = get<Chats>()
    val chat = chats.getChat(chatId) ?: finishCall(HttpStatus.NotFound)
    if (chat.user != user.id) finishCall(HttpStatus.Forbidden)
    finishCall(HttpStatus.OK, ChatResponse(chat))
}

private suspend fun Context.deleteChat()
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val chats = get<Chats>()
    if (!chats.deleteChat(chatId, user.id))
        finishCall(HttpStatus.NotFound, "聊天不存在或你没有权限删除")
    finishCall(HttpStatus.OK, "删除成功")
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

private fun Context.getToolData()
{
    val type = call.request.queryParameters["type"] ?: finishCall(HttpStatus.BadRequest, "type is required")
    val path = call.request.queryParameters["path"] ?: finishCall(HttpStatus.BadRequest, "path is required")
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val data = AiTools.getData(user.id, type, path)
    finishCall(HttpStatus.OK, data ?: AiTools.ToolData(AiTools.ToolData.Type.TEXT, "资源不存在或无法访问"))
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