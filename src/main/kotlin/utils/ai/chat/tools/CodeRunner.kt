package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.Sandbox
import moe.tachyon.quiz.utils.ai.Content
import java.io.File
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
object CodeRunner: AiToolSet.ToolProvider
{
    override val name: String get() = "执行代码"
    fun getSandbox(chat: ChatId) = Sandbox(
        id = "subquiz-ai-code-runner-$chat",
        containerTarFile = ChatFiles.sandboxImageFile(chat),
        baseImage = "python:3.9-alpine",
        memoryLimit = "512m",
        cpuLimit = "0.5",

        dirs = listOf(
            Triple(ChatFiles.getChatFilesDir(chat), "/workspace/input", false),
            Triple(ChatFiles.sandboxOutputDir(chat).apply(File::mkdirs), "/workspace/output", true),
        ),
        init = listOf(
            listOf("python3", "-m", "pip", "config", "set", "global.index-url", "https://pypi.tuna.tsinghua.edu.cn/simple"),
            listOf("python3", "-m", "pip", "config", "set", "install.trusted-host", "pypi.tuna.tsinghua.edu.cn"),
            listOf("python3", "-m", "pip", "install", "--upgrade", "pip"),
            listOf("python3", "-m", "pip", "install", "sympy"),
        ),
    )

    suspend fun executeInSandbox(
        chat: ChatId,
        timeout: Long,
        persistent: Boolean,
        cmd: List<String>,
        onMessage: suspend (stdout: String, stderr: String)->Unit = { _, _ -> }
    ): String
    {
        val sandbox = getSandbox(chat)

        val (exitCode, output, error) = sandbox.run(
            cmd = cmd,
            maxOut = 10480,
            maxErr = 2048,
            timeout = timeout,
            persistent = persistent,
            onMessage = onMessage,
        )

        return buildString()
        {
            append("Exit Code: ${exitCode ?: "程序未退出"}\n")
            if (!currentCoroutineContext().isActive) append("该进程被用户取消\n")
            if (output.isNotBlank())
            {
                append("stdout:\n```\n")
                append(output)
                append("\n```\n")
            }
            if (error.isNotBlank())
            {
                append("stderr:\n```\n")
                append(error)
                append("\n```\n")
            }
        }
    }

    @Serializable
    private data class CodeRunnerParm(
        @JsonSchema.Description("代码运行超时时间，单位毫秒，不得超过120000毫秒")
        val timeoutMs: Long,
        @JsonSchema.Description("是否保留你在虚拟机内的修改，例如创建的文件、安装的软件包等")
        val persistent: Boolean,
        @JsonSchema.Description("需要运行的Python代码")
        val code: String,
    )

    @Serializable
    private data class CmdParm(
        @JsonSchema.Description("命令运行超时时间，单位毫秒，不得超过120000毫秒")
        val timeoutMs: Long,
        @JsonSchema.Description("是否保留你在虚拟机内的修改，例如创建的文件、安装的软件包等")
        val persistent: Boolean,
        @JsonSchema.Description("需要运行的命令")
        val cmd: List<String>,
    )

    @Serializable
    private data class ShowFileParm(
        @JsonSchema.Description("文件路径，注意必须以`/workspace/output/`开头")
        val path: String,
    )

    override suspend fun AiToolSet.registerTools()
    {
        registerTool<CodeRunnerParm>(
            name = "run_python",
            displayName = "运行代码",
            description = """
                在虚拟机（操作系统为alpine Linux）中运行Python代码，返回其输出结果
                - 系统已经预装sympy。当你需要进行一些复杂数学计算时你可以使用sympy来完成计算
                - 当你需要处理数据时你可以编写Python代码来处理数据
                - 默认的工作目录是"/workspace"
                - 重要：当用户上传文件给你之后，你应该使用Python代码来处理这些文件。例如用户上传了一个DOCX文件，你可以使用python-docx库来读取文件内容，或转为PDF再使用ReadImage工具来读取
                - 用户上传到聊天的文件会被放在"/workspace/input"中，你可以读取这些文件，但注意：
                  - 文件上传后会变成两个文件，一个为{uuid}.info，另一个为{uuid}.data
                  - 你可以读取{uuid}.info文件来获取文件的原始文件名等信息
                  - 你可以读取{uuid}.data为文件的内容（二进制）除非你确定它是文本文件，否则请不要尝试直接打印它
                [[重要]]关于持久化的说明：
                每次你调用run_python或run_cmd工具时，将从最近的一次你使用了 persistent=true 的调用中恢复虚拟机的状态。
                但是，input和output不受持久化控制，因此你可以在非persistent的情况下运行代码，将文件放入output，然后使用upload_file工具将文件发送给用户。
                而其他位置的文件，均受到持久化控制。
                所以如果你：
                - 需要创建一个文件，并在后续需要使用：要么带有 persistent=true ，要么将文件放在output目录中
                - 需要用pip安装环境等：务必带有 persistent=true 参数
                - 仅读取文件/写入output中的文件，无需修改环境和其他文件：建议使用 persistent=false 参数
                总之，系统环境和除去`/input` `/output`外的全部文件系统都会恢复到上次persistent=true。
                [[重要]]如果你希望你的文件/环境被保留请启用persistent
            """.trimIndent(),
        )
        {
            sendMessage("```python\n${parm.code.replace("```", "")}\n```\n")
            if (parm.code.isBlank())
                return@registerTool AiToolInfo.ToolResult(Content("error: code must not be empty"))
            val timeout = parm.timeoutMs.coerceIn(1000, 120_000)
            return@registerTool try
            {
                val r = executeInSandbox(
                    chat = chat.id,
                    cmd = listOf("python3", "-c", parm.code),
                    persistent = parm.persistent,
                    timeout = timeout,
                    onMessage = { stdout, stderr ->
                        if (stdout.isNotEmpty()) sendMessage("<span style='white-space:pre;'>${stdout.replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")}</span>")
                        if (stderr.isNotEmpty()) sendMessage("<span style='color:red;white-space:pre;'>${stderr.replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")}</span>")
                    }
                ) + if (!parm.persistent) "由于持久化未启用，本次操作除/output目录中的内容外均会被回滚" else ""
                AiToolInfo.ToolResult(Content(r))
            }
            catch (e: Throwable)
            {
                AiToolInfo.ToolResult(Content("error: ${e.message}"))
            }
        }

        registerTool<CmdParm>(
            name = "run_cmd",
            displayName = "运行命令",
            description = """
                在虚拟机中运行命令，例如安装python包等。
                注意
                - 操作系统为alpine Linux
                - 命令要以数组形式传入，例如 ["ls", "-al"]，而不是 ["ls -al"]
                - 安装任何软件包后的第一件事情是更换国内镜像源，例如当你安装完npm后，需要在使用前先更换国内镜像
            """.trimIndent(),
        )
        {
            sendMessage("```bash\n${parm.cmd.joinToString(" ").replace("```", "")}\n```\n")
            if (parm.cmd.isEmpty()) return@registerTool AiToolInfo.ToolResult(
                Content("error: cmd must not be empty")
            )
            val timeout = parm.timeoutMs.coerceIn(1000, 120_000)
            return@registerTool try
            {
                val r = executeInSandbox(chat = chat.id, cmd = parm.cmd, persistent = parm.persistent, timeout = timeout)
                { stdout, stderr ->
                    if (stdout.isNotEmpty()) sendMessage("<span>${stdout.replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")}</span>")
                    if (stderr.isNotEmpty()) sendMessage("<span style='color:red'>${stderr.replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")}</span>")
                }
                AiToolInfo.ToolResult(Content(r))
            }
            catch (e: Throwable)
            {
                AiToolInfo.ToolResult(Content("error: ${e.message}"))
            }
        }

        registerTool<ShowFileParm>(
            name = "upload_file",
            displayName = null,
            description = """
                如果你需要将虚拟运行环境中的一个文件上传给用户，你需要：
                - 将文件放在"/workspace/output/"目录下
                - 使用该工具并传入你的文件路径（必须以"/workspace/output/"开头）
                当你使用了该工具后
                - 你指定的文件将会被转移，变成input中的两个文件{uuid}.info和{uuid}.data
                - 用户将会受到该文件
                - output中你指定的文件将被删除
            """.trimIndent(),
        )
        {
            if (!parm.path.startsWith("/workspace/output/"))
                return@registerTool AiToolInfo.ToolResult(Content("error: path must start with /workspace/output/"))
            val path = parm.path.removePrefix("/workspace/output/").removePrefix("/")
            val file = File(ChatFiles.getChatFilesDir(chat.id), ".docker_out/$path")
            if (!file.exists() || !file.isFile)
                return@registerTool AiToolInfo.ToolResult(Content("error: file not found"))
            val uuid = ChatFiles.addChatFile(chat.id, path.substringAfterLast("/"), AiToolSet.ToolData.Type.FILE, file.readBytes())
            file.delete()
            return@registerTool AiToolInfo.ToolResult(
                content = Content("file uploaded: uuid:${uuid.toHexString()}"),
                showingContent = uuid.toHexString(),
                showingType = AiToolSet.ToolData.Type.FILE
            )
        }
    }
}