@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.ai

import cn.org.subit.dataClass.*
import cn.org.subit.dataClass.ChatId.Companion.toChatIdOrNull
import cn.org.subit.database.Chats
import cn.org.subit.plugin.contentNegotiation.QuestionAnswerSerializer
import cn.org.subit.plugin.contentNegotiation.contentNegotiationJson
import cn.org.subit.route.utils.*
import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.ai.AiRequest
import cn.org.subit.utils.ai.AiResponse
import cn.org.subit.utils.ai.Role
import cn.org.subit.utils.ai.StreamAiResponse
import cn.org.subit.utils.ai.ask.AskService
import cn.org.subit.utils.ai.ask.BdfzHelperAskService
import cn.org.subit.utils.ai.ask.QuizAskService
import cn.org.subit.utils.getKoin
import cn.org.subit.utils.statuses
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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
                statuses<Message>(HttpStatus.OK, example = Message("content", "reasoning"))
                statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
            }
        })
        {
            plugin(SSE)
            handle(Context::listenChat)
        }
    }
}

private typealias Message = StreamAiResponse.Choice.Message
private typealias ChatListener = suspend (Message) -> Unit

private class ChatInfo(
    val chat: Chat,
    response: Message = Message(),
    val listeners: MutableSet<ChatListener> = hashSetOf()
)
{
    var response = response
        private set
    lateinit var job: Job
    private val mutex = Mutex()
    
    fun merge(message: AiResponse.Choice.Message)
    {
        val content = 
            if (message.content != null) (response.content ?: "") + message.content
            else response.content
        val reasoning =
            if (message.reasoningContent != null) (response.reasoningContent ?: "") + message.reasoningContent
            else response.reasoningContent
        response = Message(content, reasoning)
    }
    
    suspend fun putMessage(message: Message): Unit = mutex.withLock()
    {
        val disabledListeners = hashSetOf<ChatListener>()
        for (listener in listeners)
            runCatching { listener(message) }.onFailure { disabledListeners += listener }
        listeners -= disabledListeners
        merge(message)
    }
    
    suspend fun putListener(listener: ChatListener) = mutex.withLock()
    {
        if (listener in listeners) return@withLock
        listeners += listener
        listener(response)
    }
}
private val responseMap = ConcurrentHashMap<ChatId, ChatInfo>()
@OptIn(DelicateCoroutinesApi::class)
private val responseCoroutineScope = CoroutineScope(newFixedThreadPoolContext(10, "AiResponseCoroutineScope"))
private suspend fun startRespond(content: String, chat: Chat, model: Model): String?
{
    val newHash: String = UUID.randomUUID().toString()
    val info = ChatInfo(chat.copy(hash = newHash))
    runCatching()
    {
        if (responseMap.putIfAbsent(chat.id, info) != null) return null
        val chats: Chats by getKoin().inject()
        if (!chats.checkHash(chat.id, chat.hash)) return null
        chats.addHistory(chat.id, ChatMessage(Role.USER, content), newHash)
        info.job = responseCoroutineScope.launch()
        {
            try
            {
                model.service.ask(
                    chat.section,
                    chat.histories.map { AiRequest.Message(it.role, it.content) },
                    content,
                    info::putMessage
                )
            }
            finally
            {
                chats.addHistory(
                    chat.id,
                    ChatMessage(Role.ASSISTANT, info.response.content ?: "", info.response.reasoningContent ?: "")
                )
                responseMap.remove(chat.id)
            }
        }
    }.onFailure()
    {
        responseMap.remove(chat.id)
        throw it
    }
    return newHash
}

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
private val ser = Section.serializer(
    QuestionAnswerSerializer,
    QuestionAnswerSerializer,
    String.serializer()
)
private class SectionSerializer: KSerializer<Section<Any, Any, String>> by ser
@Serializable
@Suppress("unused")
private enum class Model(val service: AskService)
{
    BDFZ_HELPER(BdfzHelperAskService),
    QUIZ_AI(QuizAskService);
}

@Serializable
private data class CreateChatRequest(
    @Serializable(SectionSerializer::class)
    val section: Section<@Contextual Any, @Contextual Any, String>?,
    val content: String,
    val model: Model,
)

private suspend fun Context.newChat(): Nothing
{
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<CreateChatRequest>()
    val chats = get<Chats>()
    val chat = chats.createChat(user.id, body.section)
    val hash = startRespond(body.content, chat, body.model) ?: finishCall(HttpStatus.Conflict)
    finishCall(HttpStatus.OK, chat.copy(hash = hash))
}

@Serializable
private data class SendChatMessageRequest(
    val chatId: ChatId,
    val content: String,
    val model: Model = Model.BDFZ_HELPER,
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
    val hash = startRespond(body.content, chat, body.model) ?: finishCall(HttpStatus.Conflict)
    finishCall(HttpStatus.OK, hash)
}
private suspend fun Context.listenChat()
{
    val loginUser = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["chat"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val hash = call.parameters["hash"] ?: finishCall(HttpStatus.BadRequest)
    val chatInfo = responseMap[chatId] ?: finishCall(HttpStatus.OK)
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
            send(data = contentNegotiationJson.encodeToString(it), event = "message")
        }
        chatInfo.job.join()
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