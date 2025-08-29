package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content

object ShowHtml
{
    @Serializable
    private data class ShowSvgToolData(
        @JsonSchema.Description("SVG 图像内容")
        val svg: String,
    )

    @Serializable
    private data class ShowHtmlToolData(
        @JsonSchema.Description("HTML 内容")
        val html: String,
    )

    init
    {
        AiTools.registerTool<ShowSvgToolData>(
            name = "show_svg",
            displayName = null,
            description = """
                向用户展示 SVG 图像
                你可以将 SVG 图像内容传递给该工具并显示给用户。
                该工具会将 SVG 图像直接展示给用户，若成功，会告知你成功，若不成功则告知你错误信息。
                该工具在你需要画简单示意图等场景时非常有用。
            """.trimIndent()
        )
        { (chat, model, parm) ->
            val svgContent = parm.svg.trim()
            AiToolInfo.ToolResult(Content("已成功展示 SVG 图像"), svgContent)
        }

        AiTools.registerTool<ShowHtmlToolData>(
            name = "show_html",
            displayName = null,
            description = """
                向用户展示 一个 HTML 页面
                你可以将 HTML 内容传递给该工具并显示给用户。
                请注意，请确保你传递给该工具的HTML是一个完整的HTML页面内容，
                从 `<!DOCTYPE html>` 开始，到 `</html>` 结束。
                该工具会将 HTML 页面直接展示给用户，若成功，会告知你成功，若不成功则告知你错误信息。
            """.trimIndent()
        )
        { (chat, model, parm) ->
            val html = parm.html.trim()
            AiToolInfo.ToolResult(Content("已成功展示 HTML 页面"), html, AiTools.ToolData.Type.HTML)
        }
    }
}