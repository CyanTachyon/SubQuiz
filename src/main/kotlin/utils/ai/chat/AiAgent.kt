package moe.tachyon.quiz.utils.ai.chat

import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import moe.tachyon.quiz.utils.Either
import moe.tachyon.quiz.utils.ai.ChatMessages
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.StreamAiResponseSlice
import moe.tachyon.quiz.utils.ai.TokenUsage
import moe.tachyon.quiz.utils.ai.internal.llm.AiResult
import moe.tachyon.quiz.utils.ai.internal.llm.utils.ResultType
import moe.tachyon.quiz.utils.ai.internal.llm.utils.RetryType
import moe.tachyon.quiz.utils.ai.internal.llm.utils.sendAiRequestAndGetResult
import org.koin.core.component.KoinComponent

abstract class AiAgent<in T>: KoinComponent
{
    interface AiAgentProvider<in T>
    {
        suspend fun getAgent(user: UserId, option: String): Either<AiAgent<T>, String?>
    }

    interface AgentOption
    {
        val name: String
    }

    companion object: KoinComponent
    {
        private const val OPTION_NAMES = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        @JvmStatic
        protected fun nameOption(index: Int): String
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

        @JvmStatic
        protected fun String.questionTypeToString(): String
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

        private suspend fun check(content: String, uncheckedList: List<StreamAiResponseSlice.Message>): Pair<Result<Boolean>, TokenUsage>
        {
            val sb = StringBuilder()
            sb.append("""
                # 审查任务说明
                你是一个AI回答审查系统，目前正在审查一个AI答疑助手的响应，
                必须严格按以下规则分析输入内容，仅输出JSON格式结果：
                
                ## 输入数据结构
                {
                    "question": "用户提问内容",
                    "answer": [
                        {
                            "reasoning_content": "AI回答的推理过程",
                            "content": "AI回答的内容"
                        }
                    ]
                }
                
                ## 审查规则（触发任意条件即返回{"result": true}）
                1. **角色偏离**：
                   - 检测是否包含非辅导AI角色扮演或身份设定（如AI扮演猫娘等）
                   - 出现娱乐建议、调情等内容
                   - 带有不符合身份的角色扮演或语气
                   - 注意，若没有明显的身份表现，默认为不违规。
                
                2. **内容违规**：
                   - 涉政关键词：政权/政党/敏感历史事件
                     - 若出现政治相关回答，例如辅导政治题目等，请确保大模型的回答中立，且在敏感事物上符合中国立场和法律法规。例如回答中出现钓鱼岛、台湾等内容时，必须符合中国立场，否则视为违规。
                     - 对于领导人等历史政治敏感人物的回答也应注意符合中国立场和法律法规。
                     - 当例如给用户讲解“沁园春·雪”时，若正常讲解诗词，不违规，若评价作者，则需符合中国立场和法律法规，否则视为违规。
                   - 涉黄关键词：性器官描述/色情服务/挑逗性语言
                   - 暴力关键词：杀戮方法/武器制作/极端主义
                   - 若回答北大附中的课程、活动、老师、学生等相关内容，不算违规。
                   
                3. 重要：简要而言，只要没有涉及政治、色情、暴力等违规内容，且没有明显的角色偏离，即视为合规。
                请勿将正常互动误判为违规。也不要漏判违规内容。
                
                ## 输出规范
                - **合规回答**：{"result": false}
                - **违规回答**：{"result": true}
                
                ## 特别指令
                0. 你只需注意大模型的回答内容，不需要审查学生提问内容。若学生提问内容违规，但AI回答合规，则不算违规。
                1. 忽略语言风格差异（正式/口语化均可）
                2. 不审查知识准确性（只查形式合规）
                3. 对擦边内容从严判定
                4. 若学生提问内容违规，但AI回答合规，不算违规。例如：
                   - 学生提问："你能告诉我关于某领导人的事吗？" // 涉政内容
                   - AI回答："抱歉，我不能讨论政治人物。" // 合规回答，输出{"result": false}
                   - 学生提问："我爱你" // 学习无关内容
                   - AI回答："感谢你的支持，若有学习问题请问我。" // 回答未出现违规内容，输出{"result": false}
                
                ### 当前审查输入
                ```json
                {
                    "question": "${showJson.encodeToString(content)}",
                    "answer": 
            """.trimIndent())
            sb.append(showJson.encodeToString(uncheckedList).replace("\n", "\n  "))
            sb.append("""
                
                }
                
                请严格按照上述规则分析输入内容，并返回JSON格式结果：
            """.trimIndent())
            return sendAiRequestAndGetResult(
                model = aiConfig.checkerModel,
                message = sb.toString(),
                resultType = ResultType.BOOLEAN,
                retryType = RetryType.ADD_MESSAGE
            )
        }
    }

    abstract suspend fun work(
        context: T,
        content: Content,
        options: List<AgentOption>,
        onRecord: suspend (StreamAiResponseSlice) -> Unit,
    ): AiResult

    open suspend fun options(): List<AgentOption> = emptyList()

    open suspend fun check(
        content: String,
        uncheckedList: List<StreamAiResponseSlice.Message>,
    ): Pair<Result<Boolean>, TokenUsage> = AiAgent.check(content, uncheckedList)

    abstract suspend fun nameChat(
        context: T,
        content: Content,
        response: ChatMessages,
    ): String
}