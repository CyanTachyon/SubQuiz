package moe.tachyon.quiz.utils.ai.chat.tools

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
    }

    init
    {
        AiTools.registerTool<ShowSection>(
            name = "show_question",
            displayName = null,
            description = """
                向用户展示一道大题，一个大题由若干小题构成。
                该工具会把题目提供给用户，并提供查看答案按钮。
                大题适用于如有一个题目背景信息，然后有多个小题的情况，
                例如阅读理解，实验题等。
                你输入给该工具的内容会直接展示给用户，请确保内容准确无误。
                **注意**：当你需要出题给用户时，如你向用户讲解了新知识点，准备出题帮用户巩固知识，**必须**使用`show_question`工具，
                而不是直接在回答中写题目，否则用户将看不到题目。
                该工具在你需要出题给用户时非常有用。
                
                example（单个小题）:
                ```json
                {
                    "description": "", // 大题描述留空，表示没有大题，只有若干小题
                    "questions": [
                        {
                            "description": "以下哪项是质数？",
                            "type": "choice",
                            "options": ["4", "6", "9", "11"],
                            "answer": [3],
                            "analysis": "质数是指只能被1和它本身整除的自然数，11只能被1和11整除，因此11是质数。"
                        }
                    ]
                }
                ```
                
                example（多个小题）:
                ```json
                {
                    "description": "阅读下面的文章，然后回答问题。(文章内容省略)",
                    "questions": [
                        {
                            "description": "文章的主要观点是什么？",
                            "type": "essay",
                            "answer": "文章的主要观点是...",
                            "options": [],
                            "analysis": "文章通过论据A、B、C支持了其主要观点。"
                        },
                        {
                            "description": "以下哪项是文章提到的事实？",
                            "type": "choice",
                            "options": ["事实A", "事实B", "事实C", "事实D"],
                            "answer": [1],
                            "analysis": "文章中明确提到事实B，其他选项未被提及。"
                        },
                        {
                            "description": "文章中提到的事件发生在什么时间？",
                            "type": "fill",
                            "answer": "2020年",
                            "options": [],
                            "analysis": "根据文章内容，事件发生在2020年。"
                        }
                    ]
                }
                ```
                
                需要注意：
                - 每个空必须独立一道小题，不能多个问题在一个小题中，这会导致用户只能输入一个答案，无法分别作答多个空。
                - 你不应该标注题号，题号会自动生成，你只需要提供题目内容即可。
                - 若有多道独立小题，可让大题的题目内容为空，但你不应该让大题的题目内容与小题重复。
                - 你必须确保题目内容清晰明确，避免歧义，确保用户能够理解题意。
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