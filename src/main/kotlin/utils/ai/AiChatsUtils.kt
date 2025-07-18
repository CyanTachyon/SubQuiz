package moe.tachyon.quiz.utils.ai

import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.dataClass.ChatMessage
import moe.tachyon.quiz.database.Chats
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.Locks
import moe.tachyon.quiz.utils.ai.AiChatsUtils.AiChatEvent
import moe.tachyon.quiz.utils.ai.ask.AskService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*

private typealias Message = StreamAiResponse.Choice.Message
typealias ChatListener = suspend (AiChatEvent)->Unit

private const val CHECK_LENGTH = 500

object AiChatsUtils: KoinComponent
{
    const val BANNED_MESSAGE = "对不起，该聊天无法继续"
    private val logger = SubQuizLogger.getLogger<AiChatsUtils>()
    private val chats: Chats by inject()

    sealed interface AiChatEvent

    data object BannedEvent: AiChatEvent
    data object FinishedEvent: AiChatEvent
    data class MessageEvent(val message: Message): AiChatEvent

    private val chatInfoLocks = Locks<ChatId>()
    class ChatInfo(
        val chat: Chat,
        val content: String,
    )
    {
        var response = Message()
            private set
        val listeners: MutableSet<ChatListener> = hashSetOf()
        var banned: Boolean = false
            private set
        var finished: Boolean = false
            private set
        var checked = 0
        private val job = SupervisorJob()
        suspend fun join() = job.join()
        private val coroutineScope = CoroutineScope(Dispatchers.IO + job + CoroutineExceptionHandler { _, throwable ->
            if (!job.isCancelled) logger.warning("ChatInfo job failed ${chat.id}", throwable)
        })

        private suspend inline fun<T> withLock(block: () -> T): T = chatInfoLocks.withLock(chat.id, block)

        suspend fun start(service: AskService): Unit = withLock()
        {
            if (finished || banned) return

            coroutineScope.launch()
            {
                runCatching()
                {
                    service.ask(
                        chat.section,
                        chat.histories.map { AiRequest.Message(it.role, it.content) },
                        content,
                        this@ChatInfo::putMessage
                    )
                }.onFailure()
                {
                    logger.warning("ChatInfo ask failed ${chat.id}", it)
                    if (!this@ChatInfo.response.content.isNullOrEmpty())
                        putMessage(
                            StreamAiResponse.Choice.Message(
                                content = "\n\n --- \n\n"
                            )
                        )
                    putMessage(StreamAiResponse.Choice.Message(
                        content = "- 系统错误，请联系管理员"
                    ))
                    finish(withBanned = false, error = true)
                }.onSuccess {
                    runCatching { finish(withBanned = false, error = false) }
                }
                responseMap.remove(chat.id)
            }
        }

        suspend fun banned(): Unit = withLock()
        {
            if (banned) return
            finish(withBanned = true, error = false)
        }

        suspend fun check(onFinished: Boolean): Unit = withLock()
        {
            val reasoning = response.reasoningContent ?: ""
            val content = response.content ?: ""
            val len = reasoning.length + content.length
            val checked = this.checked
            if (banned || len <= checked || (!onFinished && len - checked < CHECK_LENGTH)) return
            this.checked = len
            val uncheckedReasoning =
                if (checked < reasoning.length) reasoning.substring(checked)
                else null
            val uncheckedContent =
                if (checked < reasoning.length) content
                else content.substring(checked - reasoning.length)
            coroutineScope.launch()
            {
                val illegal = AskService.check(this@ChatInfo.content, uncheckedReasoning, uncheckedContent).first
                if (illegal) return@launch banned()
            }
        }

        suspend fun merge(message: AiResponse.Choice.Message): Unit = withLock()
        {
            val content =
                if (message.content != null) (response.content ?: "") + message.content
                else response.content
            val reasoning =
                if (message.reasoningContent != null) (response.reasoningContent ?: "") + message.reasoningContent
                else response.reasoningContent
            response = Message(content, reasoning)
            check(false)
        }

        suspend fun putMessage(message: Message): Unit = withLock()
        {
            if (banned) return
            val disabledListeners = hashSetOf<ChatListener>()
            for (listener in listeners)
                runCatching { listener(MessageEvent(message)) }.onFailure { disabledListeners += listener }
            listeners -= disabledListeners
            merge(message)
        }

        suspend fun putListener(listener: ChatListener) = withLock()
        {
            if (banned) return listener(BannedEvent)
            if (listener in listeners) return
            listeners += listener
            listener(MessageEvent(response))
        }

        suspend fun finish(withBanned: Boolean, error: Boolean): Unit = withLock()
        {
            if (finished && !withBanned) return
            finished = true
            banned = banned || withBanned
            if (!error && !banned) runCatching { check(true) }
            job.complete()
            runCatching()
            {
                if (banned) for (listener in listeners) runCatching { listener(BannedEvent) }
                else for (listener in listeners) runCatching { listener(FinishedEvent) }
            }
            val message =
                if (!banned) ChatMessage(Role.ASSISTANT, response.content ?: "", response.reasoningContent ?: "")
                else ChatMessage(Role.ASSISTANT, BANNED_MESSAGE, "")
            runCatching()
            {
                chats.updateHistory(
                    chat.id,
                    chat.histories + ChatMessage(Role.USER, content) + message,
                    chat.hash,
                    banned
                )
            }
            if (banned || error) runCatching { job.cancel() }
        }
    }

    private val responseMap = hashMapOf<ChatId, ChatInfo>()

    fun getChatInfo(chatId: ChatId): ChatInfo? = responseMap[chatId]

    suspend fun startRespond(content: String, chat: Chat, service: AskService): String? = chatInfoLocks.withLock(chat.id)
    {
        if (chat.banned) return null
        val newHash: String = UUID.randomUUID().toString()
        val info = ChatInfo(chat.copy(hash = newHash), content)
        if (responseMap.putIfAbsent(chat.id, info) != null) return null
        runCatching()
        {
            if (!chats.checkHash(chat.id, chat.hash))
            {
                responseMap.remove(chat.id)
                return null
            }
            chats.updateHistory(chat.id, chat.histories + ChatMessage(Role.USER, content), newHash)
            info.start(service)
        }.onFailure()
        {
            responseMap.remove(chat.id)
            throw it
        }
        return newHash
    }
}