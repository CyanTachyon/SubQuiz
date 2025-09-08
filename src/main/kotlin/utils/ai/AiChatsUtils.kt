@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.utils.ai.chatUtils

import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.Chats
import moe.tachyon.quiz.database.Users
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.Locks
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.ai.chat.AskService
import moe.tachyon.quiz.utils.ai.chat.tools.AiTools
import moe.tachyon.quiz.utils.ai.internal.llm.AiResult
import moe.tachyon.quiz.utils.safeWithContext
import moe.tachyon.quiz.utils.toJpegBytes
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import kotlin.uuid.ExperimentalUuidApi

private typealias MessageSlice = StreamAiResponseSlice
private typealias TextMessage = StreamAiResponseSlice.Message
typealias ChatListener = suspend (AiChatsUtils.AiChatEvent)->Unit

private const val CHECK_LENGTH = 1000

object AiChatsUtils: KoinComponent
{
    /**
     * 聊天被封禁时的提示信息
     */
    const val BANNED_MESSAGE = "对不起，该聊天无法继续"

    /**
     * 系统错误时的提示信息
     */
    const val ERROR_MESSAGE = "系统错误，请重试或切换模型并重试。如果问题仍然存在，请联系管理员。"

    /**
     * 聊天结束后，强制杀死协程的时间，用于避免审查时间过长
     */
    const val FORCE_STOP_TIME = 5000L

    private val logger = SubQuizLogger.getLogger<AiChatsUtils>()
    private val chats: Chats by inject()
    private val users: Users by inject()

    sealed interface AiChatEvent

    data object BannedEvent: AiChatEvent
    data object FinishedEvent: AiChatEvent
    @Serializable data class MessageEvent(val message: MessageSlice): AiChatEvent
    @Serializable data class NameChatEvent(val name: String): AiChatEvent

    private val chatInfoLocks = Locks<ChatId>()
    class ChatInfo(
        val chat: Chat,
        val content: Content,
    )
    {
        private val checkJobs = mutableListOf<Job>()
        private var nameJob = null as Job?

        private val response: MutableList<StreamAiResponseSlice> = mutableListOf()
        val listeners: MutableSet<ChatListener> = hashSetOf()
        var banned: Boolean = false
            private set
        var finished: Boolean = false
            private set
        private var checked = 0
        private val job = SupervisorJob()
        private val askJob = Job()
        private val coroutineScope = CoroutineScope(Dispatchers.IO + job + CoroutineExceptionHandler()
        { _, throwable ->
            if (!job.isCancelled) logger.warning("error in ChatInfo job ${chat.id}", throwable)
        })


        private suspend fun<T> withLock(block: suspend () -> T): T = chatInfoLocks.withLock(chat.id, block)
        suspend fun join() = job.join()
        fun cancel() = askJob.cancel()

        suspend fun start(service: AskService): Unit = withLock()
        {
            if (finished || banned) return@withLock

            job.invokeOnCompletion()
            {
                responseMap.remove(chat.id)
            }

            coroutineScope.launch()
            {
                val res = runCatching()
                {
                    safeWithContext(askJob)
                    {
                        service.ask(
                            chat,
                            content,
                            this@ChatInfo::putMessage
                        )
                    }
                }.getOrElse { AiResult.UnknownError(ChatMessages.empty(), TokenUsage(), it) }

                try
                {
                    users.addTokenUsage(chat.user, res.usage)
                }
                catch (_: CancellationException) {}
                catch (e: Throwable)
                {
                    logger.warning("error in add token usage", e)
                }

                when (res)
                {
                    is AiResult.Cancelled,
                    is AiResult.Success      ->
                    {
                        finish(withBanned = false, res.messages)
                    }

                    is AiResult.ServiceError,
                    is AiResult.TooManyRequests,
                    is AiResult.UnknownError ->
                    {
                        when (res)
                        {
                            is AiResult.ServiceError    -> logger.warning("Got service error in chat ${chat.id}")
                            is AiResult.TooManyRequests -> logger.warning("Got too many requests in chat ${chat.id} use service $service")
                            is AiResult.UnknownError    -> logger.warning("Got unknown error in chat ${chat.id}", res.error)
                            else                        -> Unit
                        }

                        val endMsg = if (res.messages.isNotEmpty()) "\n---\n$ERROR_MESSAGE" else ERROR_MESSAGE
                        runCatching { putMessage(TextMessage(endMsg)) }
                        val resMsg = res.messages + ChatMessage(Role.ASSISTANT, endMsg)
                        finish(withBanned = false, resMsg.toList())
                    }
                }
            }
        }

        private suspend fun banned(): Unit = withLock()
        {
            finish(withBanned = true, BANNED_MESSAGE)
        }

        private suspend fun check(onFinished: Boolean): Unit = withLock()
        {
            val uncheckedList = mutableListOf<TextMessage>()
            var checked0 = this.checked
            for (message in response)
            {
                if (message !is TextMessage) continue
                if (message.content.length + message.reasoningContent.length <= checked0)
                {
                    checked0 -= message.content.length + message.reasoningContent.length
                    continue
                }
                var content = message.content
                var reasoning = message.reasoningContent
                if (checked0 < reasoning.length)
                {
                    reasoning = reasoning.substring(checked0)
                }
                else
                {
                    checked0 -= reasoning.length
                    content = content.substring(checked0)
                }
                checked0 = 0
                uncheckedList += TextMessage(
                    content = content,
                    reasoningContent = reasoning
                )
            }

            var count = 0
            for (message in uncheckedList)
                count += message.content.length + message.reasoningContent.length

            if (!onFinished && count < CHECK_LENGTH) return@withLock
            else this.checked += count

            checkJobs += coroutineScope.launch()
            {
                val res = AskService.check(this@ChatInfo.content.toText(), uncheckedList)
                users.addTokenUsage(chat.user, res.second)
                if (res.first.getOrThrow()) return@launch banned()
            }
        }

        private fun nameChat()
        {
            val chat = this.chat.copy(histories = chat.histories + ChatMessage(Role.USER, content) + response.filterIsInstance<TextMessage>().map { ChatMessage(Role.ASSISTANT, it.content, it.reasoningContent) })
            nameJob = coroutineScope.launch()
            {
                val name = AskService.nameChat(chat)
                for (listener in listeners)
                    runCatching { listener(NameChatEvent(name)) }
                runCatching { chats.updateName(chat.id, name) }
            }
        }

        private suspend fun merge(message: MessageSlice): Unit = withLock()
        {
            val lst = response.lastOrNull()
            if (message !is TextMessage)
            {
                response += message
                return@withLock
            }
            else if (lst !is TextMessage)
            {
                response += message
                return@withLock
            }
            val content = lst.content + message.content
            val reasoning = lst.reasoningContent + message.reasoningContent
            response[response.lastIndex] = TextMessage(content = content, reasoningContent = reasoning)
            check(false)
        }

        private suspend fun putMessage(message: MessageSlice): Unit = withLock()
        {
            if (banned) return@withLock
            val disabledListeners = hashSetOf<ChatListener>()
            for (listener in listeners)
                runCatching { listener(MessageEvent(message)) }.onFailure { disabledListeners += listener }
            listeners -= disabledListeners
            merge(message)
        }

        suspend fun putListener(listener: ChatListener) = withLock()
        {
            if (banned) return@withLock listener(BannedEvent)
            if (listener in listeners) return@withLock
            listeners += listener
            response.forEach {
                runCatching { listener(MessageEvent(it)) }
            }
            if (finished)
            {
                if (banned) for (listener in listeners) runCatching { listener(BannedEvent) }
                else for (listener in listeners) runCatching { listener(FinishedEvent) }
            }
        }

        private suspend fun finish(withBanned: Boolean, res: String): Unit =
            finish(withBanned, listOf(ChatMessage(Role.ASSISTANT, res)))

        private suspend fun finish(withBanned: Boolean, res: List<ChatMessage>): Unit = withLock()
        {
            if (finished && !withBanned) return@withLock
            if (banned && !withBanned) return@withLock
            finished = true
            banned = banned || withBanned
            if (!banned) runCatching { check(true) }
            runCatching { nameChat() }
            if (!banned) runCatching()
            {
                coroutineScope.launch()
                {
                    delay(FORCE_STOP_TIME)
                    checkJobs.forEach { runCatching { it.cancel() } }
                    nameJob?.cancel()
                }
            }
            job.complete()
            runCatching()
            {
                if (banned) for (listener in listeners) runCatching { listener(BannedEvent) }
                else for (listener in listeners) runCatching { listener(FinishedEvent) }
            }
            runCatching()
            {
                chats.updateHistory(
                    chat.id,
                    chat.histories + ChatMessage(Role.USER, content) + res,
                    chat.hash,
                    banned
                )
                response.clear()
            }
            if (banned) runCatching { job.cancel() }
        }
    }

    private val responseMap = Collections.synchronizedMap(hashMapOf<ChatId, ChatInfo>())

    suspend fun getChatInfo(chat: ChatId): ChatInfo? = chatInfoLocks.withLock(chat)
    {
        return@withLock responseMap[chat]
    }

    suspend fun startRespond(content: Content, chat: Chat, service: AskService): String? = chatInfoLocks.withLock(chat.id)
    {
        if (chat.banned) return@withLock null
        val newHash: String = UUID.randomUUID().toString()
        val info = ChatInfo(chat.copy(hash = newHash), content)
        if (responseMap.putIfAbsent(chat.id, info) != null) return@withLock null
        runCatching()
        {
            if (!chats.checkHash(chat.id, chat.hash))
            {
                responseMap.remove(chat.id)
                return@withLock null
            }
            chats.updateHistory(chat.id, chat.histories + ChatMessage(Role.USER, content), newHash)
            info.start(service)
        }.onFailure()
        {
            responseMap.remove(chat.id)
            throw it
        }
        return@withLock newHash
    }

    suspend fun cancelChat(chat: ChatId): Unit = chatInfoLocks.withLock(chat)
    {
        responseMap[chat]?.cancel()
    }

    suspend fun deleteChat(chat: ChatId, user: UserId): Boolean = chatInfoLocks.withLock(chat)
    {
        if (chat in responseMap) return@withLock false
        if (chats.deleteChat(chat, user))
        {
            ChatFiles.deleteChatFiles(chat)
            return@withLock true
        }
        return@withLock false
    }

    interface MakeContentResult
    {
        val content: Content?
        val error: String?
        data class Success(override val content: Content): MakeContentResult
        {
            override val error: String? = null
        }
        data class Failure(override val error: String): MakeContentResult
        {
            override val content: Content? = null
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun makeContent(chat: Chat, content: Content): MakeContentResult
    {
        if (content.count { it is ContentNode.Image || it is ContentNode.File } > 10)
            return MakeContentResult.Failure("最多只能上传10个附件")

        if (content.filterIsInstance<ContentNode.Image>().any { it.image.url.length > 1030 * 1024 * 4 / 3 }) // 1030KB,稍作余量, base64会膨胀到4/3倍，因此这里乘以4/3
            return MakeContentResult.Failure("图片过大，单张图片不能超过1MB")
        if (content.filterIsInstance<ContentNode.File>().any { it.file.url.length > 10 * 1030 * 1024 * 4 / 3 }) // 10MB
            return MakeContentResult.Failure("文件过大，单个文件不能超过10MB")

        val fileBytes = mutableMapOf<Int, ByteArray>()

        content.forEachIndexed()
        { index, it ->
            when (it)
            {
                is ContentNode.Text  -> return@forEachIndexed
                is ContentNode.File  ->
                {
                    runCatching()
                    {
                        if (it.file.url.startsWith("uuid:")) return@forEachIndexed
                        val bytes = it.file.url.decodeBase64Bytes()
                        if (bytes.size > 10 * 1024 * 1024) // 10MB
                            return MakeContentResult.Failure("文件过大，单个文件不能超过10MB")
                        fileBytes[index] = bytes
                    }.getOrElse()
                    {
                        return MakeContentResult.Failure("图片格式错误或无法读取")
                    }
                }
                is ContentNode.Image ->
                {
                    runCatching()
                    {
                        if (it.image.url.startsWith("uuid:")) return@forEachIndexed
                        val bytes = it.image.url.decodeBase64Bytes().toJpegBytes()
                        if (bytes.size > 1024 * 1024) // 1MB
                            return MakeContentResult.Failure("图片过大，单张图片不能超过1MB")
                        fileBytes[index] = bytes
                    }.getOrElse()
                    {
                        return MakeContentResult.Failure("图片格式错误或无法读取")
                    }
                }
            }
        }

        val usedSize = ChatFiles.getUsedChatFileSize(chat.copy(histories = chat.histories + ChatMessage(Role.USER, content)))

        if (usedSize + fileBytes.values.sumOf { it.size } > 50 * 1024 * 1024)
            return MakeContentResult.Failure("文件过大，当前聊天记录中总文件不能超过50MB")

        val rContent = content.mapIndexed()
        { index, it ->
            when (it)
            {
                is ContentNode.Text  -> it
                is ContentNode.File  ->
                {
                    if (it.file.url.startsWith("uuid:")) return@mapIndexed it
                    val bytes = fileBytes[index]!!
                    val uuid = ChatFiles.addChatFile(chat.id, it.file.filename, AiTools.ToolData.Type.FILE, bytes)
                    ContentNode.file(it.file.filename, "uuid:${uuid.toHexString()}")
                }

                is ContentNode.Image ->
                {
                    if (it.image.url.startsWith("uuid:")) return@mapIndexed it
                    val bytes = fileBytes[index]!!
                    val uuid = ChatFiles.addChatFile(chat.id, "img.jpeg", AiTools.ToolData.Type.IMAGE, bytes)
                    ContentNode.image("uuid:${uuid.toHexString()}")
                }
            }
        }.let(::Content)

        ChatFiles.clearUnusedChatFiles(chat.copy(histories = chat.histories + ChatMessage(Role.USER, rContent)))

        return MakeContentResult.Success(rContent)
    }
}