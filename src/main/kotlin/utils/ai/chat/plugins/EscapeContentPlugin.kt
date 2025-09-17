package moe.tachyon.quiz.utils.ai.chat.plugins

import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.ContentNode
import moe.tachyon.quiz.utils.ai.internal.llm.BeforeLlmRequest
import moe.tachyon.quiz.utils.ai.internal.llm.BeforeLlmRequest.BeforeRequestContext
import moe.tachyon.quiz.utils.ai.internal.llm.LlmLoopPlugin.Context
import moe.tachyon.quiz.utils.ai.internal.llm.model
import moe.tachyon.quiz.utils.ai.internal.llm.requestMessage

class EscapeContentPlugin(private val chat: ChatId): BeforeLlmRequest
{
    private fun parseContent(chat: ChatId, content: Content, imageable: Boolean): Content
    {
        val res = mutableListOf<ContentNode>()
        val cur = StringBuilder()
        content.content.forEach()
        {
            when (it)
            {
                is ContentNode.Text  -> cur.append(it.text)
                is ContentNode.Image ->
                {
                    if (imageable)
                    {
                        if (cur.isNotEmpty())
                        {
                            res.add(ContentNode(cur.toString()))
                            cur.clear()
                        }
                        ChatFiles.parseUrl(chat, it.image.url)?.let { url -> ContentNode.image(url) }?.let(res::add)
                    }
                    else cur.append("`image={url='${it.image.url}'}`")
                }

                is ContentNode.File  -> cur.append("`file={name='${it.file.filename}', url='${it.file.url}'}`")
            }
        }
        if (cur.isNotEmpty()) res.add(ContentNode(cur.toString()))
        return Content(res)
    }


    context(_: Context, _: BeforeRequestContext)
    override suspend fun beforeRequest()
    {
        requestMessage = requestMessage.map()
        {
            it.copy(content = parseContent(chat, it.content, model.imageable))
        }.toChatMessages()
    }
}