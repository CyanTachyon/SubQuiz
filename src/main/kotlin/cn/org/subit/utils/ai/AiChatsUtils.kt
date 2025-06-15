package cn.org.subit.utils.ai

import cn.org.subit.dataClass.Chat
import cn.org.subit.dataClass.ChatId
import cn.org.subit.dataClass.ChatMessage
import cn.org.subit.database.Chats
import cn.org.subit.utils.Locks
import cn.org.subit.utils.ai.AiChatsUtils.AiChatEvent
import cn.org.subit.utils.ai.ask.AskService
import cn.org.subit.utils.ai.ask.BdfzHelperAskService
import cn.org.subit.utils.ai.ask.QuizAskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*

private typealias Message = StreamAiResponse.Choice.Message
typealias ChatListener = suspend (AiChatEvent)->Unit

object AiChatsUtils: KoinComponent
{
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val chats: Chats by inject()

    sealed interface AiChatEvent

    data object BannedEvent: AiChatEvent
    data object FinishedEvent: AiChatEvent
    data class MessageEvent(val message: Message): AiChatEvent

    @Serializable
    @Suppress("unused")
    enum class Model(val service: AskService)
    {
        BDFZ_HELPER(BdfzHelperAskService),
        QUIZ_AI(QuizAskService);
    }

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
        private val checkJobs = mutableListOf<Job>()
        lateinit var job: Job

        private suspend inline fun<T> withLock(block: () -> T): T = chatInfoLocks.withLock(chat.id, block)

        suspend fun banned(): Unit = withLock()
        {
            if (banned) return
            finish(true)
        }

        suspend fun check(): Unit = withLock()
        {
            val content = response.content ?: return
            val checked = this.checked
            this.checked = content.length
            checkJobs += coroutineScope.launch()
            {
                val illegal = AskService.check(this@ChatInfo.content, content.substring(checked)).first
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
            if (content != null && content.length - checked >= 1000) check()
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

        suspend fun finish() = finish(false)
        suspend fun finish(withBanned: Boolean): Unit = withLock()
        {
            if (finished && !withBanned) return
            finished = true
            banned = banned || withBanned
            if (!banned && checked < content.length) runCatching { check() }
            runCatching()
            {
                if (banned) for (listener in listeners) runCatching { listener(BannedEvent) }
                else for (listener in listeners) runCatching { listener(FinishedEvent) }
            }
            runCatching { listeners.clear() }
            val message =
                if (!banned) ChatMessage(Role.ASSISTANT, response.content ?: "", response.reasoningContent ?: "")
                else ChatMessage(Role.ASSISTANT, "对不起，该聊天无法继续", "")
            runCatching()
            {
                chats.updateHistory(
                    chat.id,
                    chat.histories + ChatMessage(Role.USER, content) + message,
                    chat.hash,
                    banned
                )
            }
            if (banned) runCatching { job.cancel() }
        }.also()
        {
            if (withBanned) return@also
            val checkJobs = withLock { this.checkJobs.toList() } // 复制一份，避免其他协程修改时出现并发问题
            checkJobs.forEach { runCatching { it.join() } }
            // checkJob的join必须在withLock外部执行，因为check任务如果进入了banned流程会上锁，导致死锁
        }
    }

    private val responseMap = hashMapOf<ChatId, ChatInfo>()

    fun getChatInfo(chatId: ChatId): ChatInfo? = responseMap[chatId]

    suspend fun startRespond(content: String, chat: Chat, model: Model): String? = chatInfoLocks.withLock(chat.id)
    {
        if (chat.banned) return null
        val newHash: String = UUID.randomUUID().toString()
        val info = ChatInfo(chat.copy(hash = newHash), content)
        runCatching()
        {
            if (responseMap.putIfAbsent(chat.id, info) != null) return null
            if (!chats.checkHash(chat.id, chat.hash)) return null
            chats.updateHistory(chat.id, chat.histories + ChatMessage(Role.USER, content), newHash)
            info.job = coroutineScope.launch()
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
                    try
                    {
                        info.finish()
                    }
                    finally
                    {
                        responseMap.remove(chat.id)
                    }
                }
            }
        }.onFailure()
        {
            responseMap.remove(chat.id)
            throw it
        }
        return newHash
    }
}