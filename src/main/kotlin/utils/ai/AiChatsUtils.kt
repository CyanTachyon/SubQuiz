@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.utils.ai.chatUtils

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.database.Chats
import moe.tachyon.quiz.database.Users
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.Locks
import moe.tachyon.quiz.utils.ai.ChatMessage
import moe.tachyon.quiz.utils.ai.Role
import moe.tachyon.quiz.utils.ai.StreamAiResponseSlice
import moe.tachyon.quiz.utils.ai.ask.AskService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*

private typealias MessageSlice = StreamAiResponseSlice
private typealias TextMessage = StreamAiResponseSlice.Message
private typealias ToolCallMessage = StreamAiResponseSlice.ToolCall
typealias ChatListener = suspend (AiChatsUtils.AiChatEvent)->Unit

private const val CHECK_LENGTH = 500

object AiChatsUtils: KoinComponent
{
    const val BANNED_MESSAGE = "对不起，该聊天无法继续"
    const val ERROR_MESSAGE = "系统错误，请联系管理员"
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
        val content: String,
    )
    {
        private val response: MutableList<StreamAiResponseSlice> = mutableListOf()
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

            job.invokeOnCompletion()
            {
                responseMap.remove(chat.id)
            }

            coroutineScope.launch()
            {
                runCatching()
                {
                    service.ask(
                        chat.section,
                        chat.histories,
                        chat.user,
                        content,
                        this@ChatInfo::putMessage
                    )
                }.onFailure()
                {
                    logger.warning("ChatInfo ask failed ${chat.id}", it)
                    response.clear()
                    putMessage(TextMessage(content = ERROR_MESSAGE))
                    finish(withBanned = false, error = true)
                }.onSuccess()
                {
                    logger.warning("error in add token usage")
                    {
                        users.addTokenUsage(chat.user, it.second)
                    }
                    finish(withBanned = false, error = false, it.first)
                }
            }
        }

        suspend fun banned(): Unit = withLock()
        {
            if (banned) return
            finish(withBanned = true, error = false)
        }

        suspend fun check(onFinished: Boolean): Unit = withLock()
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

            if (!onFinished && count < CHECK_LENGTH) return
            else this.checked += count

            coroutineScope.launch()
            {
                val res = AskService.check(this@ChatInfo.content, uncheckedList)
                users.addTokenUsage(chat.user, res.second)
                if (res.first) return@launch banned()
            }
        }

        private fun nameChat()
        {
            val chat = this.chat.copy(histories = chat.histories + ChatMessage(Role.USER, content) + response.filterIsInstance<TextMessage>().map { ChatMessage(Role.ASSISTANT, it.content, it.reasoningContent) })
            coroutineScope.launch()
            {
                val name = AskService.nameChat(chat)
                for (listener in listeners)
                    runCatching { listener(NameChatEvent(name)) }
                runCatching { chats.updateName(chat.id, name) }
            }
        }

        suspend fun merge(message: MessageSlice): Unit = withLock()
        {
            val lst = response.lastOrNull()
            if (message !is TextMessage)
            {
                response += message
                return
            }
            else if (lst !is TextMessage)
            {
                response += message
                return
            }
            val content = lst.content + message.content
            val reasoning = lst.reasoningContent + message.reasoningContent
            response[response.lastIndex] = TextMessage(content = content, reasoningContent = reasoning)
            check(false)
        }

        suspend fun putMessage(message: MessageSlice): Unit = withLock()
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
            response.forEach {
                runCatching { listener(MessageEvent(it)) }
            }
            if (finished)
            {
                if (banned) for (listener in listeners) runCatching { listener(BannedEvent) }
                else for (listener in listeners) runCatching { listener(FinishedEvent) }
            }
        }

        suspend fun finish(
            withBanned: Boolean,
            error: Boolean,
            res: List<ChatMessage> =
                if (withBanned) listOf(ChatMessage(Role.ASSISTANT, BANNED_MESSAGE))
                else if (error) listOf(ChatMessage(Role.ASSISTANT, ERROR_MESSAGE))
                else error("finish called without messages"),
        ): Unit = withLock()
        {
            if (finished && !withBanned) return
            finished = true
            banned = banned || withBanned
            if (!banned) runCatching { check(true) }
            runCatching { nameChat() }
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