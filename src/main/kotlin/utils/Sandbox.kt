package moe.tachyon.quiz.utils

import kotlinx.coroutines.*
import moe.tachyon.quiz.logger.SubQuizLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

    val liveTime: Long = 5 * 60 * 1000, // 毫秒
)
{
    companion object
    {
        private val running = ConcurrentHashMap.newKeySet<String>()
        private val sandboxJobs = ConcurrentHashMap<String, Pair<Job, Boolean>>()
        private val locks = Locks<String>()

        init
        {
            Runtime.getRuntime().addShutdownHook(Thread()
            {
                val logger = SubQuizLogger.getLogger("Sandbox-ShutdownHook")
                logger.info("JVM关闭，清理所有运行中的容器...")
                running.forEach()
                { id ->
                    Sandbox(
                        id = id,
                        containerTarFile = File(""),
                        baseImage = "alpine",
                        memoryLimit = "128m",
                        cpuLimit = "0.1",
                    ).apply()
                    {
                        logger.severe("清理容器${id}失败")
                        {
                            cleanupContainer()
                        }
                    }
                }
            })
        }

        fun execCommand(command: List<String>): Triple<Int, String, String>
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
    ): Triple<Int?, String, String> = locks.withLock(id)
    {
        try
        {
            startSandbox()
            val execResult = executeWithTimeout(cmd, maxOut, maxErr, timeout)
            return@withLock execResult
        }
        catch (e: Exception)
        {
            logger.warning("执行出错: ${e.message}")
            throw e
        }
        finally
        {
            addCloseJob(persistent)
        }
    }

    private suspend fun startSandbox(): Unit = locks.withLock(id)
    {
        if (!running.add(id)) return@withLock
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
        // 删除临时镜像
        execCommand(listOf("docker", "rmi", "${id}_restored"))
        Thread.sleep(1000)
        if (firstRun && init.isNotEmpty())
        {
            logger.fine("执行初始化命令...")
            for (initCmd in init)
            {
                println("run $initCmd")
                val initResult = executeWithTimeout(initCmd, 0, 0, 60_000)
                if (initResult.first != 0)
                    error("初始化命令执行失败: ${initCmd.joinToString(" ")}\n${initResult.third}")
            }
            logger.info("容器${id}初始化完成")
        }
    }

    private suspend fun addCloseJob(persistent: Boolean) = locks.withLock(id)
    {
        val (oldJob, oldPersistent) = sandboxJobs[id] ?: (null to false)
        oldJob?.cancel()
        val newJob = closeJob(persistent || oldPersistent)
        sandboxJobs[id] = newJob to (persistent || oldPersistent)
    }

    private fun closeJob(persistent: Boolean) = CoroutineScope(Dispatchers.IO).launch()
    {
        logger.info("add close job for container $id, liveTime=${liveTime}ms, persistent=$persistent")
        delay(liveTime)
        close(persistent)
    }

    private suspend fun close(persistent: Boolean) = locks.withLock(id)
    {
        try
        {
            if (persistent)
            {
                logger.fine("保存容器${id}状态...")
                saveContainerState()
            }
        }
        finally
        {
            try
            {
                cleanupContainer()
            }
            finally
            {
                running.remove(id)
                sandboxJobs.remove(id)
            }
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
                currentCoroutineContext().ensureActive()
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
                currentCoroutineContext().ensureActive()
                if (stderr.length > maxErr) continue
                stderr.append(buffer, 0, read)
                if (stderr.length > maxErr)
                    stderr.append("\n...error output truncated...\n")
            }
        }

        val completed = process.waitFor(timeout, TimeUnit.MILLISECONDS)

        job0.cancel()
        job1.cancel()

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
            logger.info("容器${id}已清理")
        }
        catch (e: Exception)
        {
            logger.warning("清理容器时出错: ${e.message}")
        }
    }
}