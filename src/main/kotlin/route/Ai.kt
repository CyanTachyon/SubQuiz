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
import io.ktor.util.*
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonElement
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.ChatId.Companion.toChatIdOrNull
import moe.tachyon.quiz.database.Chats
import moe.tachyon.quiz.database.Users
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.QuestionAnswerSerializer
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.ai.chat.AskService
import moe.tachyon.quiz.utils.UserConfigKeys
import moe.tachyon.quiz.utils.ai.chat.QuizAskService
import moe.tachyon.quiz.utils.ai.chat.plugins.ContextCompressor
import moe.tachyon.quiz.utils.ai.chat.tools.AiLibrary
import moe.tachyon.quiz.utils.ai.chat.tools.AiToolSet
import moe.tachyon.quiz.utils.ai.chatUtils.AiChatsUtils
import moe.tachyon.quiz.utils.leftOrElse
import moe.tachyon.quiz.utils.statuses
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
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
                statuses<ChatId>(HttpStatus.OK, example = ChatId(1))
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

        get("/options", {
            description = "获取AI模型选项"
            request()
            {
                queryParameter<String>("model") { required = true }
            }
            response()
            {
                statuses<List<String>>(HttpStatus.OK)
            }
        }, Context::getServiceOptions)

        get("/customModel", {
            description = "获取自定义AI聊天模型"
            response()
            {
                statuses<QuizAskService.CustomModelSetting?>(HttpStatus.OK)
                statuses(HttpStatus.Unauthorized)
            }
        }, Context::getCustomModel)

        put("/customModel", {
            description = "设置自定义AI聊天模型"
            request()
            {
                body<QuizAskService.CustomModelSetting>()
                {
                    required = true
                    description = "自定义模型设置"
                }
            }
            response()
            {
                statuses(HttpStatus.OK)
                statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
            }
        }, Context::setCustomModel)

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

            get("/share", {
                description = "获得AI聊天的分享链接"
                response()
                {
                    statuses<String>(HttpStatus.OK, example = "share-hash")
                    statuses(HttpStatus.Unauthorized, HttpStatus.Forbidden, HttpStatus.NotFound)
                }
            }, Context::shareChat)

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
                    statuses<ChatSseMessage>(HttpStatus.OK, example = ChatSseMessage(Content("content"), "reasoning"))
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

        route("/lib")
        {
            get("/files", {
                description = "获取AI知识库文件列表"
                response()
                {
                    statuses<List<String>>(HttpStatus.OK, example = listOf("/example.md"))
                    statuses(HttpStatus.Unauthorized)
                }
            }, Context::getLibraryFiles)

            route({
                request()
                {
                    queryParameter<String>("path")
                    {
                        required = true
                        description = "文件路径"
                    }
                }
            })
            {
                get("/file", {
                    description = "获取AI知识库文件内容"
                    response()
                    {
                        statuses<String>(HttpStatus.OK, example = "# 示例文件")
                        statuses(HttpStatus.Unauthorized, HttpStatus.NotFound)
                    }
                }, Context::getLibraryFile)

                post("/file", {
                    description = "上传AI知识库文件"
                    request()
                    {
                        body<String>()
                        {
                            required = true
                            description = "文件内容"
                        }
                    }
                    response()
                    {
                        statuses(HttpStatus.OK)
                        statuses(HttpStatus.Unauthorized, HttpStatus.NotFound)
                    }
                }, Context::uploadLibraryFile)

                delete("/file", {
                    description = "删除AI知识库文件"
                    response()
                    {
                        statuses(HttpStatus.OK)
                        statuses(HttpStatus.Unauthorized, HttpStatus.NotFound)
                    }
                }, Context::deleteLibraryFile)
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

    post("/translateImage", {
        description = "翻译图片"
        request()
        {
            body<Translate>()
            {
                required = true
                description = "需要翻译的图片的base64编码"
            }
        }
        response()
        {
            statuses<TranslatedImage>(HttpStatus.OK, example = TranslatedImage("data:image/png;base64,..."))
            statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
        }
    }, Context::translateImage)

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

    post("/essayCorrection", {
        description = "作文批改"
        request()
        {
            body<EssayCorrectionBody>()
            {
                required = true
                description = "作文批改内容"
            }
        }
        response()
        {
            statuses<ChatId>(HttpStatus.OK, example = ChatId(1))
            statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
        }
    }, Context::essayCorrection)
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
    val model: String,
    val content: Content,
    val options: List<String>
)

private suspend fun Context.newChat(): Nothing
{
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<CreateChatRequest>()
    val chats = get<Chats>()
    val service = AskService.getService(user.id, body.model).leftOrElse { finishCall(HttpStatus.BadRequest.subStatus(it ?: "模型不存在")) }
    val chat = chats.createChat(user.id, body.section)
    val res = logger.warning("Failed to start chat for user ${user.id} with model ${body.model}")
    {
        val content = AiChatsUtils.makeContent(chat, body.content)
        if (content !is AiChatsUtils.MakeContentResult.Success)
            finishCall(HttpStatus.BadRequest.subStatus(content.error))
        AiChatsUtils.startRespond(content.content, chat, service, service.options().filter { it.name in body.options } ) ?: finishCall(HttpStatus.Conflict)
        chat.id
    }.onFailure { AiChatsUtils.deleteChat(chat.id, user.id) }.getOrThrow()
    finishCall(HttpStatus.OK, res)
}

@Serializable
private data class SendChatMessageRequest(
    val chatId: ChatId,
    val model: String,
    val hash: String,
    val content: Content,
    val regenerate: Boolean = false,
    val options: List<String>
)

@Serializable
private data class SendChatMessageResponse(
    val content: Content,
    val hash: String,
)

private suspend fun Context.sendChatMessage(): Nothing
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
    val service = AskService.getService(loginUser.id, body.model).leftOrElse { finishCall(HttpStatus.BadRequest.subStatus(it ?: "模型不存在")) }
    val content = AiChatsUtils.makeContent(chat, body.content)
    if (content !is AiChatsUtils.MakeContentResult.Success)
        finishCall(HttpStatus.BadRequest.subStatus(content.error))
    val hash = AiChatsUtils.startRespond(content.content, chat, service, service.options().filter { it.name in body.options }) ?: finishCall(HttpStatus.Conflict)
    finishCall(HttpStatus.OK, SendChatMessageResponse(content.content, hash))
}

private suspend fun Context.getCustomModel(): Nothing
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val customModel = get<Users>().getCustomSetting<QuizAskService.CustomModelSetting>(user.id, UserConfigKeys.CUSTOM_MODEL_CONFIG_KEY)
    finishCall(HttpStatus.OK, customModel)
}

private suspend fun Context.setCustomModel(): Nothing
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<QuizAskService.CustomModelSetting>()
    if (body.model.isBlank()) get<Users>().setCustomSetting(user.id, UserConfigKeys.CUSTOM_MODEL_CONFIG_KEY, null)
    else get<Users>().setCustomSetting(user.id, UserConfigKeys.CUSTOM_MODEL_CONFIG_KEY, body)
    finishCall(HttpStatus.OK)
}

@Serializable
private data class ChatModelResponse(
    val model: String,
    val displayName: String,
    val toolable: Boolean,
)

private suspend fun Context.getChatModels(): Nothing
{
    val customModel =
        getLoginUser()?.id?.let { get<Users>().getCustomSetting<QuizAskService.CustomModelSetting>(it, UserConfigKeys.CUSTOM_MODEL_CONFIG_KEY) }
    val models = aiConfig.chats.map { ChatModelResponse(it.model, it.displayName, aiConfig.models[it.model]!!.toolable) }
    val rModels =
        if (customModel != null)
            listOf(ChatModelResponse(QuizAskService.CUSTOM_MODEL_NAME, customModel.model, customModel.toolable)) + models
        else models
    finishCall(HttpStatus.OK, rModels,)
}

private suspend fun Context.getServiceOptions(): Nothing
{
    val model = call.queryParameters["model"] ?: finishCall(HttpStatus.BadRequest)
    val service = AskService.getService(loginUser.id, model).leftOrElse { finishCall(HttpStatus.BadRequest.subStatus(it ?: "模型不存在")) }
    finishCall(HttpStatus.OK, service.options().map { it.name })
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
    val mark: Mark = Mark()
)
{
    @Serializable
    data class Mark(
        val showingType: AiToolSet.ToolData.Type? = null,
        val id: String = "",
        val label: String = "",
    )
}

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
    {
        constructor(role: Role, message: ChatSseMessage) : this(role, listOf(message))
    }

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

            val tools = AiToolSet.defaultToolSet().getTools(chat, null)
            val list = chat.histories.mapNotNull()
            { msg ->
                return@mapNotNull when (msg.role)
                {
                    is Role.USER -> History(Role.USER, ChatSseMessage(msg.content, ""))
                    is Role.SYSTEM -> null
                    is Role.MARKING if (msg.role.type == ContextCompressor.MARKING_TYPE) -> History(Role.ASSISTANT, ChatSseMessage(Content(""), "", ChatSseMessage.Mark(label = "压缩上下文")))
                    is Role.MARKING -> null
                    is Role.SHOWING_DATA -> History(Role.ASSISTANT, ChatSseMessage(msg.content, "", ChatSseMessage.Mark(msg.role.type)))
                    is Role.ASSISTANT -> History(Role.ASSISTANT, ChatSseMessage(msg.content, msg.reasoningContent))
                    is Role.TOOL -> History(Role.ASSISTANT, ChatSseMessage(Content(), msg.reasoningContent, ChatSseMessage.Mark(id = msg.role.id, label = tools.firstOrNull { it.name == msg.role.name }?.displayName ?: return@mapNotNull null)))
                }
            }
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

    sseHeaders()

    val chatInfo = AiChatsUtils.getChatInfo(chatId) ?: run()
    {
        val res = SSEServerContent(call)
        {
            send(event = "end", data = "truly end")
        }
        return call.respond(HttpStatusCode.OK, res)
    }

    if (chatInfo.chat.user != loginUser.id && !loginUser.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    if (chatInfo.chat.hash != hash) finishCall(HttpStatus.Conflict)

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
                    val msg = when(it.message)
                    {
                        is StreamAiResponseSlice.Message ->
                            ChatSseMessage(Content(it.message.content), it.message.reasoningContent)
                        is StreamAiResponseSlice.ToolCall ->
                            if (it.message.tool.displayName != null)
                                ChatSseMessage(Content(), "", ChatSseMessage.Mark(id = it.message.id, label = it.message.tool.displayName))
                            else null
                        is StreamAiResponseSlice.ShowingTool ->
                            ChatSseMessage(Content(it.message.content), "", ChatSseMessage.Mark(it.message.showingType))
                        is StreamAiResponseSlice.ToolMessage ->
                            ChatSseMessage(Content(), it.message.reasoningContent, ChatSseMessage.Mark(id = it.message.id))
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

private suspend fun Context.getChatList(): Nothing
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chats = get<Chats>()
    val (begin, count) = call.getPage()
    val chatList = chats.getChats(user.id, begin, count)
    finishCall(HttpStatus.OK, chatList.map { ChatResponse(it) })
}

private suspend fun Context.getChat(): Nothing
{
    val chatId = call.parameters["id"] ?: finishCall(HttpStatus.BadRequest)
    val chats = get<Chats>()
    val (chat, useShareHash) =
        chatId.toChatIdOrNull()?.let { chats.getChat(it)?.to(false) }
        ?: chats.getChatByShareHash(chatId)?.to(true)
        ?: finishCall(HttpStatus.NotFound)
    if (!useShareHash && chat.user != getLoginUser()?.id && !getLoginUser().hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    finishCall(HttpStatus.OK, ChatResponse(chat))
}

private suspend fun Context.deleteChat(): Nothing
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    if (!AiChatsUtils.deleteChat(chatId, user.id))
        finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.cancelChat(): Nothing
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val chat = get<Chats>().getChat(chatId) ?: finishCall(HttpStatus.NotFound)
    if (chat.user != user.id) finishCall(HttpStatus.Forbidden)
    AiChatsUtils.cancelChat(chatId)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.shareChat(): Nothing
{
    val user = call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val chatId = call.parameters["id"]?.toChatIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val chats = get<Chats>()
    val chat = chats.getChat(chatId) ?: finishCall(HttpStatus.NotFound)
    if (chat.user != user.id && !user.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    val shareHash = chats.getShareHash(chatId) ?: finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK, shareHash)
}

private suspend fun Context.getToolData(): Nothing
{
    val type = call.queryParameters["type"] ?: finishCall(HttpStatus.BadRequest.subStatus("type is required"))
    val path = call.queryParameters["path"] ?: finishCall(HttpStatus.BadRequest.subStatus("path is required"))
    val chatId = call.parameters["id"] ?: finishCall(HttpStatus.BadRequest.subStatus("chat id is required"))
    val chats = get<Chats>()
    val (chat, useShareHash) =
        chatId.toChatIdOrNull()?.let { chats.getChat(it)?.to(false) }
        ?: chats.getChatByShareHash(chatId)?.to(true)
        ?: finishCall(HttpStatus.NotFound)
    if (!useShareHash && chat.user != getLoginUser()?.id && !getLoginUser().hasGlobalAdmin()) finishCall(HttpStatus.NotFound)
    val data = AiToolSet.defaultToolSet().getData(chat, type, path)
    finishCall(HttpStatus.OK, data ?: AiToolSet.ToolData(AiToolSet.ToolData.Type.TEXT, "资源不存在或无法访问"))
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun Context.getChatFile(info: Boolean): Nothing
{
    val fileUuid = call.parameters["file"] ?: finishCall(HttpStatus.BadRequest.subStatus("file is required"))
    val chatId = call.parameters["id"] ?: finishCall(HttpStatus.BadRequest.subStatus("chat id is required"))
    val download = call.request.queryParameters["download"]?.toBooleanStrictOrNull() ?: false
    val chats = get<Chats>()
    val (chat, useShareHash) =
        chatId.toChatIdOrNull()?.let { chats.getChat(it)?.to(false) }
        ?: chats.getChatByShareHash(chatId)?.to(true)
        ?: finishCall(HttpStatus.NotFound)
    if (!useShareHash && chat.user != getLoginUser()?.id && !getLoginUser().hasGlobalAdmin()) finishCall(HttpStatus.NotFound)
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
private data class TranslatedText(val text: String, val reasoning: String)
@Serializable
private data class Translate(
    val data: String,
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
            AiTranslate.translate(body.data, body.lang0, body.lang1, body.twoWay)
            { content, reasoning ->
                send(event = "message", data = contentNegotiationJson.encodeToString(TranslatedText(content, reasoning)))
            }
        }
    }
    return call.respond(HttpStatusCode.OK, res)
}

@Serializable
private data class TranslatedImage(val data: String)

private suspend fun Context.translateImage(): Nothing
{
    call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val body = call.receive<Translate>()

    if ((call.request.contentLength() ?: Long.MAX_VALUE) > 21 * 1024 * 1024 * 4 / 3)
        finishCall(HttpStatus.BadRequest.subStatus("图片过大，最大支持20MB"))

    val img = runCatching { ImageIO.read(body.data.split(",").last().decodeBase64Bytes().inputStream()) }
        .getOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("无法解析图片"))
    val res = AiTranslate.translate(img, body.lang0, body.lang1, body.twoWay)
    val bytes = ByteArrayOutputStream().also { ImageIO.write(res, "jpeg", it) }.toByteArray().encodeBase64()
    finishCall(HttpStatus.OK, TranslatedImage("data:image/jpeg;base64,$bytes"))
}

@Serializable
private data class Text(val text: String)
@Serializable
private data class ImageToTextRequest(
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

@Serializable
private data class EssayCorrectionBody(
    val requirement: String,
    val essay: String,
)

private suspend fun Context.essayCorrection()
{
    val body = call.receive<EssayCorrectionBody>()
    if (body.essay.isBlank()) finishCall(HttpStatus.BadRequest.subStatus("请输入作文内容"))
    loginUser
    val res = EssayCorrection.correctEssay(body.requirement, body.essay)
    get<Users>().addTokenUsage(loginUser.id, res.second)
    res.first.onSuccess()
    {
        finishCall(HttpStatus.OK, it)
    }.onFailure()
    {
        finishCall(HttpStatus.InternalServerError.subStatus("服务错误，请稍后再试或联系管理员"))
    }
}

private suspend fun Context.getLibraryFiles(): Nothing =
    finishCall(HttpStatus.OK, AiLibrary.getAllFiles(loginUser.id))

private suspend fun Context.getLibraryFile(): Nothing
{
    val path = call.request.queryParameters["path"] ?: finishCall(HttpStatus.BadRequest.subStatus("path is required"))
    val text = AiLibrary.getFileText(loginUser.id, path) ?: finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK, text)
}

private suspend fun Context.uploadLibraryFile(): Nothing
{
    val path = call.request.queryParameters["path"] ?: finishCall(HttpStatus.BadRequest.subStatus("path is required"))
    val body = call.receiveText()
    if (body.isBlank()) finishCall(HttpStatus.BadRequest.subStatus("文件内容不能为空"))
    AiLibrary.remove(loginUser.id, path, true)
    if (path.substringAfterLast(".", "") !in AiLibrary.textFileExtensions)
        finishCall(HttpStatus.BadRequest.subStatus("目前仅支持文本文件"))
    AiLibrary.insertFile(loginUser.id, path, body)
    AiLibrary.updateUserLibrary(loginUser.id)
    AiLibrary.cleanup(loginUser.id)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.deleteLibraryFile(): Nothing
{
    val path = call.request.queryParameters["path"] ?: finishCall(HttpStatus.BadRequest.subStatus("path is required"))
    AiLibrary.remove(loginUser.id, path, true)
    finishCall(HttpStatus.OK)
}