package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.database.Sections
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object Quiz: KoinComponent, AiToolSet.ToolProvider
{
    override val name: String get() = "搜索题库"
    private val sections: Sections by inject()

    @Serializable
    private data class SearchQuizToolData(
        @JsonSchema.Description("搜索题库的关键字")
        val keyword: String,
        @JsonSchema.Description("搜索的题目数量, 不填默认为10")
        val count: Int = 10,
    )

    override suspend fun AiToolSet.registerTools()
    {
        registerTool<SearchQuizToolData>(
            name = "search_questions",
            displayName = "搜索题库",
            description = """
                该工具用于搜索题库中的题目。只能通过关键字搜索。因此请尽可能保证你的`keyword`在题目中出现。
                *该工具暂时不支持多关键字搜索。*
                当用户学习某个知识点后，你可以搜索题目并考察用户对该知识点的掌握情况。
            """.trimIndent(),
        )
        {
            sendMessage("搜索题目: ${parm.keyword.split(" ").joinToString(" ") { s -> "`$s`" }}")
            val keyword = parm.keyword
            val sections = sections.recommendSections(chat.user, keyword, null, parm.count).list
            if (sections.isEmpty())
            {
                AiToolInfo.ToolResult(Content("没有找到相关题目"))
            }
            else
            {
                AiToolInfo.ToolResult(Content(showJson.encodeToString(sections)))
            }
        }
    }
}