package moe.tachyon.quiz.utils.ai.chat.plugins

import moe.tachyon.quiz.utils.ai.ChatMessage
import moe.tachyon.quiz.utils.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.quiz.utils.ai.internal.llm.BeforeLlmRequest
import moe.tachyon.quiz.utils.ai.internal.llm.LlmLoopPlugin
import moe.tachyon.quiz.utils.ai.internal.llm.requestMessage

class PromptPlugin(private vararg val prompt: ChatMessage): BeforeLlmRequest
{
    context(_: LlmLoopPlugin.Context, _: BeforeLlmRequest.BeforeRequestContext)
    override suspend fun beforeRequest()
    {
        requestMessage = prompt.toList().toChatMessages() + requestMessage
    }
}