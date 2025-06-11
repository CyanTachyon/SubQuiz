package cn.org.subit.utils.ai.ask

import cn.org.subit.config.cosConfig
import cn.org.subit.dataClass.Section
import cn.org.subit.utils.COS
import cn.org.subit.utils.ai.AiImage
import cn.org.subit.utils.ai.AiRequest.Message
import cn.org.subit.utils.ai.Role
import cn.org.subit.utils.ai.StreamAiResponse

abstract class AskService
{
    companion object
    {
        private const val OPTION_NAMES = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val codeBlockRegex = Regex("```.*$", RegexOption.MULTILINE)
        private const val IMAGE_REGEX = "!\\[([^]]*)]\\(([^)\\s]+)(?:\\s+[\"']([^\"']*)[\"'])?\\)"
        private val imageMarkdownRegex = Regex(IMAGE_REGEX)
        private val imageSplitRegex = Regex("(?=$IMAGE_REGEX)|(?<=$IMAGE_REGEX)")

        private fun String.removeCodeBlock(): String
        {
            return this.replace(codeBlockRegex, "")
        }

        private fun nameOption(index: Int): String
        {
            val sb = StringBuilder()
            var i = index
            while (i >= 0)
            {
                sb.append(OPTION_NAMES[i % OPTION_NAMES.length])
                i = i / OPTION_NAMES.length - 1
            }
            return sb.reverse().toString()
        }

        private fun Any?.answerToString(): String
        {
            return when (this)
            {
                is String -> removeCodeBlock()
                is Number -> nameOption(toInt())
                is Boolean -> if (this) "正确" else "错误"
                else -> "无答案"
            }
        }

        private fun String.questionTypeToString(): String
        {
            return when (this)
            {
                "single" -> "单选题"
                "multiple" -> "多选题"
                "judge" -> "判断题"
                "fill" -> "填空题"
                "essay" -> "简答题"
                else -> "未知题型"
            }
        }
    }

    protected suspend fun makePrompt(
        section: Section<Any, Any, String>?,
        escapeImage: Boolean,
    ): Message
    {
        if (section?.questions?.isEmpty() == true) error("Section must contain at least one question.")
        val sb = StringBuilder()

        sb.append(
            $$$"""
            # 角色设定
            
            你是一款名为SubQuiz的智能答题系统中的学科辅导AI，当前正在帮助学生理解题目。
            
            # 核心指令
            
            你需要根据以下信息回答学生的问题：

        """.trimIndent())

        if (section?.questions?.size == 1)
        {
            sb.append("""
                ## 题目 (${section.questions.first().type.questionTypeToString()})
                ```
            """.trimIndent())
            section.description.removeCodeBlock().takeIf(CharSequence::isNotBlank)?.let { "$it\n" }?.let(sb::append)
            section.questions.first().description.removeCodeBlock().takeIf(CharSequence::isNotBlank)?.let { "$it\n" }?.let(sb::append)
            section.questions.first().options?.mapIndexed { index, string -> "${nameOption(index)}. $string\n" }?.forEach(sb::append)
            sb.append("```\n\n")

            sb.append("""
                ## 学生答案
                ```
            """.trimIndent())
            section.questions.first().userAnswer.answerToString().removeCodeBlock().ifBlank { "学生未作答" }.let { "$it\n" }.let(sb::append)
            sb.append("```\n\n")

            sb.append("""
                ## 标准答案
                ```
            """.trimIndent())
            section.questions.first().answer.answerToString().removeCodeBlock().ifBlank { "无标准答案" }.let { "$it\n" }.let(sb::append)

            sb.append("""
                ## 答案解析
                ```
            """.trimIndent())
            section.questions.first().analysis.removeCodeBlock().ifBlank { "无解析" }.let { "$it\n" }.let(sb::append)
            sb.append("```\n\n")

            if (escapeImage)
            {
                val images = COS.getImages(section.id).filter { "/$it" in sb }
                if (images.isNotEmpty())
                {
                    sb.append("## 题目和解析中所使用的图片\n")
                    describeImage(section, images)
                }
            }
        }
        else if (section != null)
        {
            sb.append("## 题目内容\n")
            section
                .description
                .removeCodeBlock()
                .takeIf(CharSequence::isNotBlank)
                ?.let { "### 大题题干\n\n```\n$it\n```\n\n" }
                ?.let(sb::append)

            sb.append("### 小题列表\n")
            section.questions.forEachIndexed()
            { index, question ->
                sb.append("#### 小题${index + 1} (${question.type.questionTypeToString()})\n")

                question
                    .description
                    .removeCodeBlock()
                    .let { "$it\n${question.options?.mapIndexed { index, string -> "${nameOption(index)}. $string" }?.joinToString("\n").orEmpty()}" }
                    .ifBlank { "该小题无题干" }
                    .let { "##### 小题题干\n\n```\n$it\n```\n\n" }
                    .let(sb::append)

                sb.append("##### 学生答案\n```\n")
                question
                    .userAnswer
                    .answerToString()
                    .removeCodeBlock()
                    .ifBlank { "学生未作答" }
                    .let { "$it\n" }
                    .let(sb::append)
                sb.append("```\n\n")

                sb.append("##### 标准答案\n```\n")
                question
                    .answer
                    .answerToString()
                    .removeCodeBlock()
                    .ifBlank { "无标准答案" }
                    .let { "$it\n" }
                    .let(sb::append)
                sb.append("```\n\n")

                sb.append("##### 答案解析\n```\n")
                question
                    .analysis
                    .removeCodeBlock()
                    .ifBlank { "无解析" }
                    .let { "$it\n" }
                    .let(sb::append)
                sb.append("```\n\n")
            }

            if (escapeImage)
            {
                val images = COS.getImages(section.id).filter { "/$it" in sb }
                if (images.isNotEmpty())
                {
                    sb.append("### 题目和解析中所使用的图片\n")
                    describeImage(section, images)
                }
            }
        }
        sb.append("""
            ## 回答要求
            1. **精准定位**：明确学生问题指向的题号或解析片段（如："针对小题3第二问..."）
            2. **分层解释**：
               - 先指出学生原答案的合理/错误点
               - 用★符号分步骤重构解题逻辑
               - 关键概念用括号标注定义（如："加速度(速度变化率)"）
            3. **关联解析**：将标准解析转化为学生更容易理解的表达，避免术语堆砌
            4. **错误预防**：针对常见误解补充1个典型错误案例
            5. **检查闭环**：结尾用提问确认学生是否理解（如："这样解释后，你对XX步骤清楚了吗？"）
            6. **范围限定**：你只应该回答题目相关问题，当用户提出与题目无关的问题时，请礼貌地告知用户你只能回答题目相关问题。
            7. **格式要求**：所有回答需要以markdown格式给出。公式必须用$（行内公式）或$$（块级公式）包裹。
            
            **接下来学生会向你提问，请开始你的辅导回答：**
        """.trimIndent())
        if (escapeImage || section == null) return Message(Role.SYSTEM, sb.toString())

        val res = sb.toString()
        val images = COS.getImages(section.id).map { "/$it" }.filter { it in res }
        if (images.isEmpty()) return Message(Role.SYSTEM, res)

        val parts = res.split(imageSplitRegex)
        val messages = mutableListOf<Message.Content>()
        for (part in parts)
        {
            if (part.isBlank()) continue
            val matchResult = imageMarkdownRegex.find(part)
            if (matchResult == null)
            {
                messages.add(Message.Content.text(part))
                continue
            }
            val imageUrl = matchResult.groupValues[2]
            if (imageUrl in images)
            {
                val url =
                    cosConfig.cdnUrl +
                    if (section.id.value > 0) "/section_images/${section.id}/$imageUrl"
                    else "/exam_images/${-section.id.value}/$imageUrl"
                messages.add(Message.Content.image(url))
            }
            else messages.add(Message.Content.text(part))
        }
        return Message(Role.SYSTEM, messages)
    }

    private suspend fun describeImage(
        section: Section<Any, Any, String>,
        images: List<String>,
    ): String
    {
        if (images.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("""
            注意：
            上述题目内容和解析中所包含的图片以已markdown语法的形式呈现，但由于你无法直接查看图片内容，
            因此图片内容通过其他大语言模型描述的方式呈现。
            该描述内容无法保证与图片内容完全一致，
            如题目中对图片内容有说明，请以题目中的描述为准。
        """.trimIndent())
        sb.append("\n\n")

        images.forEach()
        {
            val description = AiImage.describeImage(section.id, it)

            sb.append("图片：$it\n")
            sb.append("描述：\n```\n")
            sb.append(description)
            sb.append("\n```\n\n")
        }
        return sb.toString()
    }

    abstract suspend fun ask(
        section: Section<Any, Any, String>?,
        histories: List<Message>,
        content: String,
        onRecord: suspend (StreamAiResponse.Choice.Message) -> Unit,
    )
}