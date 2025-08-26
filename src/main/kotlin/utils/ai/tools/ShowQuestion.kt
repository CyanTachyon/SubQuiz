package moe.tachyon.quiz.utils.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content

object ShowQuestion
{
    private fun markdownWrap(str: String): JsonElement
    {
        val map = mutableMapOf<String, JsonElement>()
        map["type"] = JsonPrimitive("markdown")
        map["content"] = JsonPrimitive(str)
        return JsonObject(map)
    }

    @Serializable
    data class ShowSection(
        @JsonSchema.Description("大题描述，Markdown 格式，若无大题描述则为空字符串")
        val description: String,
        val questions: List<ShowQuestion>
    )
    {
        fun toSection(): Section<Any, Nothing?, JsonElement> = Section(
            id = SectionId(0),
            type = SectionTypeId(0),
            description = markdownWrap(this.description),
            weight = 50,
            available = true,
            questions = questions.map(ShowQuestion::toQuestion)
        )
    }

    @Serializable
    data class ShowQuestion(
        @JsonSchema.Description("题目描述，Markdown 格式")
        val description: String,
        @JsonSchema.Description("题目类型, choice: 选择题(包括单选和多选), judge: 判断题, fill: 填空题, essay: 简答题")
        val type: String,
        @JsonSchema.Description("答案，若为填空/简答，则为一个markdown字符串表示标准答案.\n" +
                                "若为判断，则为一个布尔值表示正确答案.\n" +
                                "若为选择题，则为一个数组，表示正确选项的索引（索引从0开始）")
        val answer: JsonElement,
        @JsonSchema.Description("选项，若为填空/简答/判断题，则为空数组.\n" +
                                "若为选择题，则为一个数组，表示所有选项的内容")
        val options: List<String>,
        @JsonSchema.Description("解析，Markdown 格式，若无解析则为空字符串")
        val analysis: String
    )
    {

        fun toQuestion(): Question<Any, Nothing?, JsonElement> = when (type)
        {
            "choice" ->
            {
                if (answer !is JsonArray) error("选择题答案必须是数组")
                val answerList = answer.map()
                {
                    if (it !is JsonPrimitive) error("选择题答案数组元素必须是整数")
                    val index = it.intOrNull ?: error("选择题答案数组元素必须是整数")
                    if (index < 0 || index >= options.size) error("选择题答案索引越界: $index")
                    index
                }
                if (answerList.isEmpty()) error("选择题答案不能为空")
                if (answerList.toSet().size != answerList.size) error("选择题答案不能有重复选项")
                when (answerList.size)
                {
                    1 -> SingleChoiceQuestion(
                        description = markdownWrap(description),
                        options = options.map(::markdownWrap),
                        answer = answerList[0],
                        userAnswer = null,
                        analysis = markdownWrap(analysis)
                    )

                    else -> MultipleChoiceQuestion(
                        description = markdownWrap(description),
                        options = options.map(::markdownWrap),
                        answer = answerList,
                        userAnswer = null,
                        analysis = markdownWrap(analysis)
                    )
                }
            }

            "judge" ->
            {
                if (answer !is JsonPrimitive) error("判断题答案必须是布尔值")
                val ans = answer.booleanOrNull ?: error("判断题答案必须是布尔值")
                JudgeQuestion(
                    description = markdownWrap(description),
                    answer = ans,
                    userAnswer = null,
                    analysis = markdownWrap(analysis)
                )
            }

            "fill" ->
            {
                if (answer !is JsonPrimitive) error("填空题答案必须是字符串")
                val ans = answer.content
                FillQuestion(
                    description = markdownWrap(description),
                    answer = markdownWrap(ans),
                    userAnswer = null,
                    analysis = markdownWrap(analysis)
                )
            }

            "essay" ->
            {
                if (answer !is JsonPrimitive) error("简答题答案必须是字符串")
                val ans = answer.content
                FillQuestion(
                    description = markdownWrap(description),
                    answer = markdownWrap(ans),
                    userAnswer = null,
                    analysis = markdownWrap(analysis)
                )
            }

            else -> error("未知的题目类型: $type")
        }

        fun toSection(): Section<Any, Nothing?, JsonElement> = Section(
            id = SectionId(0),
            type = SectionTypeId(0),
            description = markdownWrap(this.description),
            weight = 50,
            available = true,
            questions = listOf(this.toQuestion())
        )
    }

    init
    {
        AiTools.registerTool<ShowSection>(
            name = "show_question",
            displayName = null,
            description = """
                向用户展示一道大题，一个大题由若干小题构成，若只希望展示一道小题请使用 show_single_question 工具。
                该工具会把题目提供给用户，并提供查看答案按钮。
                大题适用于如有一个题目背景信息，然后有多个小题的情况，
                例如阅读理解，实验题等。
                你输入给该工具的内容会直接展示给用户，请确保内容准确无误。
            """.trimIndent(),
        )
        { (chat, model, parm) ->
            runCatching()
            {
                val section = parm.toSection()
                AiToolInfo.ToolResult(
                    content = Content("你的题目已经展示给用户。"),
                    showingContent = contentNegotiationJson.encodeToString(listOf(section)),
                    showingType = AiTools.ToolData.Type.QUIZ,
                )
            }.getOrElse()
            {
                AiToolInfo.ToolResult(Content("题目格式有误: ${it.message}"))
            }
        }

        AiTools.registerTool<ShowQuestion>(
            name = "show_single_question",
            displayName = null,
            description = """
                向用户展示一道题目，题目可以是选择题，判断题，填空题，简答题等。
                该工具会把题目提供给用户，并提供查看答案按钮。
                你输入给该工具的内容会直接展示给用户，请确保内容准确无误。
                
                example:
                ```json
                {
                    "description": "以下哪项是质数？",
                    "type": "choice",
                    "options": ["4", "6", "9", "11"],
                    "answer": [3],
                    "analysis": "质数是指只能被1和它本身整除的自然数，11只能被1和11整除，因此11是质数。"
                }
                ```
            """.trimIndent(),
        )
        { (chat, model, parm) ->
            runCatching()
            {
                val section = parm.toSection()
                AiToolInfo.ToolResult(
                    content = Content("你的题目已经展示给用户。"),
                    showingContent = contentNegotiationJson.encodeToString(listOf(section)),
                    showingType = AiTools.ToolData.Type.QUIZ,
                )
            }.getOrElse()
            {
                AiToolInfo.ToolResult(Content("题目格式有误: ${it.message}"))
            }
        }
    }
}