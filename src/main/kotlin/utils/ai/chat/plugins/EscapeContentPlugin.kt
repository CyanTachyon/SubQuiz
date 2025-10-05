package moe.tachyon.quiz.utils.ai.chat.plugins

import io.ktor.util.*
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.ContentNode
import moe.tachyon.quiz.utils.ai.chat.tools.ReadImage
import moe.tachyon.quiz.utils.ai.internal.llm.BeforeLlmRequest
import moe.tachyon.quiz.utils.ai.internal.llm.BeforeLlmRequest.BeforeRequestContext
import moe.tachyon.quiz.utils.ai.internal.llm.LlmLoopPlugin.Context
import moe.tachyon.quiz.utils.ai.internal.llm.PluginScope

class EscapeContentPlugin(private val chat: ChatId): BeforeLlmRequest
{
    private fun parseContent(chat: ChatId, content: Content, imageable: Boolean): Content
    {
        val res = mutableListOf<ContentNode>()
        val cur = StringBuilder()
        fun pushCur()
        {
            if (cur.isNotEmpty())
            {
                res.add(ContentNode(cur.toString()))
                cur.clear()
            }
        }
        content.content.forEach()
        {
            when (it)
            {
                is ContentNode.Text  -> cur.append(it.text)
                is ContentNode.Image ->
                {
                    if (imageable)
                    {
                        cur.append("`image(url='${it.image.url}'):`")
                        pushCur()
                        ChatFiles.parseUrl(chat, it.image.url)?.let { url -> ContentNode.image(url) }?.let(res::add)
                    }
                    else cur.append("`image={url='${it.image.url}'}`")
                }

                is ContentNode.File  ->
                {
                    cur.append("`file={name='${it.file.filename}', url='${it.file.url}'}`")
                    if (imageable)
                    {
                        val url = ChatFiles.parseUrl(chat, it.file.url)
                        if (url != null && url.startsWith("data:"))
                        {
                            cur.append("以下是该pdf的截图：")
                            pushCur()
                            url.let()
                            { file ->
                                val data = file.substringAfter("base64,", "").decodeBase64Bytes()
                                val imgs = ReadImage.imgs(data)
                                imgs?.map { img -> ContentNode.image(img) }?.let(res::addAll)
                            }
                        }
                    }
                }
            }
        }
        if (cur.isNotEmpty()) res.add(ContentNode(cur.toString()))
        return Content(res)
    }


    context(_: Context, _: BeforeRequestContext)
    override suspend fun PluginScope.beforeRequest()
    {
        requestMessage = requestMessage.map()
        {
            it.copy(content = parseContent(chat, it.content, model.imageable))
        }.toChatMessages()
    }
}