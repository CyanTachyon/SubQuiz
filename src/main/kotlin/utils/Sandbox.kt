package moe.tachyon.quiz.utils

import kotlinx.coroutines.*
import moe.tachyon.quiz.logger.SubQuizLogger
import java.io.File
import java.util.concurrent.TimeUnit

class Sandbox(
    val id: String,
    val containerTarFile: File,
    val dockerPath: String = "docker",
    val baseImage: String,
    val memoryLimit: String,
    val cpuLimit: String,

    val dirs: List<Triple<File, String, Boolean>> = emptyList(), // Pair<hostDir, containerDir, writeable>
    val init: List<List<String>> = emptyList(), // 初始化命令列表
)
{
    init
    {
        if (id.lowercase() != id) error("Container ID must be lowercase.")
    }

    private val logger = SubQuizLogger.getLogger<Sandbox>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler()
    { _, throwable ->
        logger.severe("sandbox error", throwable)
    })

    /**
     * 在指定目录中运行Docker容器并执行命令
     * @param cmd 要执行的命令列表
     * @param timeout 命令执行超时时间（毫秒）
     * @return Triple<退出码, 标准输出, 标准错误>
     */
    suspend fun run(
        cmd: List<String>,
        maxOut: Int,
        maxErr: Int,
        timeout: Long,
        persistent: Boolean,
    ): Triple<Int?, String, String>
    {
        try
        {
            val (baseImage, firstRun) = if (containerTarFile.exists())
            {
                logger.fine("从保存的容器文件系统恢复镜像...")
                val importImageResult = execCommand(listOf("docker", "import", containerTarFile.absolutePath, "${id}_restored"))
                if (importImageResult.first != 0)
                    error("导入容器文件系统失败: ${importImageResult.third}")
                "${id}_restored" to false
            }
            else
            {
                logger.fine("使用基础镜像创建容器...")
                this.baseImage to true
            }

            // 2. 启动容器
            logger.info("启动容器: $id")
            val runCommand = listOf(
                dockerPath, "run", "-d",
                "--name", id,
                "--memory", memoryLimit,
                "--cpus", cpuLimit,
                "--pids-limit", "100",
                "--tmpfs", "/tmp:rw,size=200m",
            ) + dirs.flatMap()
            { (hostDir, containerDir, writeable) ->
                listOf("-v", "${hostDir.absolutePath}:$containerDir:${if (writeable) "rw" else "ro"}")
            } + listOf(
                "-w", "/workspace",
                "--log-driver", "none",
                baseImage,
                "tail", "-f", "/dev/null"
            )

            val runResult = execCommand(runCommand)
            if (runResult.first != 0)
                error("启动容器失败: ${runResult.third}")
            Thread.sleep(1000)
            if (firstRun && init.isNotEmpty())
            {
                logger.fine("执行初始化命令...")
                for (initCmd in init)
                {
                    val initResult = executeWithTimeout(initCmd, 0, 0, 60_000)
                    if (initResult.first != 0)
                        error("初始化命令执行失败: ${initCmd.joinToString(" ")}\n${initResult.third}")
                }
            }
            val execResult = executeWithTimeout(cmd, maxOut, maxErr, timeout)
            if (persistent)
            {
                logger.fine("保存容器${id}状态...")
                saveContainerState()
            }
            return execResult
        }
        catch (e: Exception)
        {
            logger.warning("执行出错: ${e.message}")
            cleanupContainer()
            throw e
        }
        finally
        {
            cleanupContainer()
            execCommand(listOf("docker", "rmi", "${id}_restored"))
        }
    }

    /**
     * 带超时执行命令
     */
    private suspend fun executeWithTimeout(
        cmd: List<String>,
        maxOut: Int,
        maxErr: Int,
        timeout: Long,
    ): Triple<Int?, String, String>
    {
        logger.fine("在容器${id}中执行命令(maxOut=$maxOut, maxErr=$maxErr, timeout=${timeout}ms): ${cmd.joinToString(" ")}")
        val execCommand = listOf("docker", "exec", id) + cmd

        val process = ProcessBuilder(execCommand).start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val job0 = coroutineScope.launch()
        {
            val reader = process.inputStream.bufferedReader()
            val buffer = CharArray(1024)
            var read: Int
            while (reader.read(buffer).also { read = it } != -1)
            {
                if (stdout.length > maxOut) continue
                stdout.append(buffer, 0, read)
                if (stdout.length > maxOut)
                    stdout.append("\n...output truncated...\n")
            }
        }

        val job1 = coroutineScope.launch()
        {
            val reader = process.errorStream.bufferedReader()
            val buffer = CharArray(1024)
            var read: Int
            while (reader.read(buffer).also { read = it } != -1)
            {
                if (stderr.length > maxErr) continue
                stderr.append(buffer, 0, read)
                if (stderr.length > maxErr)
                    stderr.append("\n...error output truncated...\n")
            }
        }

        val completed = process.waitFor(timeout, TimeUnit.MILLISECONDS)

        job0.cancelAndJoin()
        job1.cancelAndJoin()

        return if (completed)
        {
            logger.fine("命令执行完成，退出码: ${process.exitValue()}")
            Triple(process.exitValue(), stdout.toString(), stderr.toString())
        }
        else
        {
            logger.fine("命令执行超时，强制终止进程")
            process.destroyForcibly().destroyForcibly().destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            val exitCode = runCatching()
            {
                process.exitValue()
            }.getOrNull()
            Triple(exitCode, stdout.toString(), stderr.toString())
        }
    }

    /**
     * 保存容器状态
     */
    private fun saveContainerState()
    {
        val exportResult = execCommand(listOf("docker", "export", "-o", containerTarFile.absolutePath, id))
        if (exportResult.first != 0)
            error("导出容器文件系统失败: ${exportResult.third}")
        logger.fine("容器${id}文件系统已导出到: ${containerTarFile.absolutePath}")
    }

    /**
     * 清理容器
     */
    private fun cleanupContainer()
    {
        try
        {
            execCommand(listOf("docker", "stop", id))
            execCommand(listOf("docker", "rm", id))
        }
        catch (e: Exception)
        {
            logger.warning("清理容器时出错: ${e.message}")
        }
    }

    /**
     * 执行系统命令
     */
    private fun execCommand(command: List<String>): Triple<Int, String, String>
    {
        val process = ProcessBuilder(command).start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line -> stdout.append(line).append("\n") }
        }

        process.errorStream.bufferedReader().use { reader ->
            reader.forEachLine { line -> stderr.append(line).append("\n") }
        }

        val exitCode = process.waitFor()
        return Triple(exitCode, stdout.toString(), stderr.toString())
    }
}