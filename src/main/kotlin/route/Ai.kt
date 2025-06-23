@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.ai

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
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.ChatId.Companion.toChatIdOrNull
import moe.tachyon.quiz.database.Chats
import moe.tachyon.quiz.plugin.contentNegotiation.QuestionAnswerSerializer
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.ai.AiChatsUtils
import moe.tachyon.quiz.utils.ai.StreamAiResponse
import moe.tachyon.quiz.utils.statuses

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
                statuses<Chat>(HttpStatus.OK, example = Chat.example)
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

        get({
            description = "获取AI聊天列表"
            request()
            {
                paged()
            }
            response()
            {
                statuses<Slice<Chat>>(HttpStatus.OK, example = sliceOf(Chat.example))
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
                statuses<Chat>(HttpStatus.OK, example = Chat.example)
                statuses(HttpStatus.Unauthorized, HttpStatus.NotFound)
            }
        }, Context::getChat)

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
            response {
                statuses<StreamAiResponse.Choice.Message>(HttpStatus.OK, example = StreamAiResponse.Choice.Message("content", "reasoning"))
                statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
            }
        })
        {
            plugin(SSE)
            handle(Context::listenChat)
        }
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
    val model: AiChatsUtils.Model,
)

private suspend fun Context.newChat(): Nothing
{
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<CreateChatRequest>()
    val chats = get<Chats>()
    val chat = chats.createChat(user.id, body.section)
    val hash = AiChatsUtils.startRespond(body.content, chat, body.model) ?: finishCall(HttpStatus.Conflict)
    finishCall(HttpStatus.OK, chat.copy(hash = hash))
}

@Serializable
private data class SendChatMessageRequest(
    val chatId: ChatId,
    val content: String,
    val model: AiChatsUtils.Model = AiChatsUtils.Model.BDFZ_HELPER,
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
    val hash = AiChatsUtils.startRespond(body.content, chat, body.model) ?: finishCall(HttpStatus.Conflict)
    finishCall(HttpStatus.OK, hash)
}
private suspend fun Context.listenChat()
{
    val loginUser = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["chat"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val hash = call.parameters["hash"] ?: finishCall(HttpStatus.BadRequest)
    val chatInfo = AiChatsUtils.getChatInfo(chatId) ?: finishCall(HttpStatus.OK)
    if (chatInfo.chat.user != loginUser.id) finishCall(HttpStatus.Forbidden)
    if (chatInfo.chat.hash != hash) finishCall(HttpStatus.Conflict)

    call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header(HttpHeaders.Connection, "keep-alive")
    call.response.header("X-Accel-Buffering", "no")

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
                is AiChatsUtils.MessageEvent ->
                    send(event = "message", data = contentNegotiationJson.encodeToString(it.message))
            }
        }
        runCatching { chatInfo.join() }
    }
    return call.respond(res)
}

private suspend fun Context.getChatList()
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chats = get<Chats>()
    val (begin, count) = call.getPage()
    val chatList = chats.getChats(user.id, begin, count)
    finishCall(HttpStatus.OK, chatList)
}

private suspend fun Context.getChat()
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val chats = get<Chats>()
    val chat = chats.getChat(chatId) ?: finishCall(HttpStatus.NotFound)
    if (chat.user != user.id) finishCall(HttpStatus.Forbidden)
    finishCall(HttpStatus.OK, chat)
}