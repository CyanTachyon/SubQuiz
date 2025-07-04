package moe.tachyon.quiz.utils

import io.ktor.server.application.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import moe.tachyon.quiz.console.AnsiStyle.Companion.RESET
import moe.tachyon.quiz.console.SimpleAnsiColor.Companion.CYAN
import moe.tachyon.quiz.console.SimpleAnsiColor.Companion.PURPLE
import moe.tachyon.quiz.logger.SubQuizLogger
import org.koin.core.component.KoinComponent
import java.io.File
import kotlin.system.exitProcess

@Suppress("unused")
object Power: KoinComponent
{
    val logger = SubQuizLogger.getLogger()

    fun shutdown(code: Int, cause: String = "unknown"): Nothing
    {
        val application = runCatching { getKoin().get<Application>() }.getOrNull()
        application.shutdown(code, cause)
    }
    fun Application?.shutdown(code: Int, cause: String = "unknown"): Nothing
    {
        logger.warning("${PURPLE}Server is shutting down: ${CYAN}$cause${RESET}")
        // 尝试主动结束Ktor, 这一过程不一定成功, 例如Ktor本来就在启动过程中出错将关闭失败
        if (this != null) logger.warning("Failed to stop Ktor: ")
        {
            monitor.raise(ApplicationStopPreparing, environment)
            engine.stop()
            @OptIn(InternalAPI::class)
            runBlocking { this@shutdown.disposeAndJoin() }
            logger.info("Ktor is stopped.")
        }
        else logger.warning("Application is null")
        startShutdownHook(code)
        exitProcess(code)
    }

    private fun startShutdownHook(code: Int)
    {
        val hook = Thread()
        {
            try
            {
                Thread.sleep(3000)
                logger.severe("检测到程序未退出，尝试强制终止")
                Runtime.getRuntime().halt(code)
            }
            catch (e: InterruptedException)
            {
                Thread.currentThread().interrupt()
            }
        }
        hook.isDaemon = true
        hook.start()
    }

    fun startMonitoring() = runCatching()
    {
        val file = File(this.javaClass.protectionDomain.codeSource.location.toURI())
        val lst = file.lastModified()
        val thread = Thread()
        {
            try
            {
                while (true)
                {
                    Thread.sleep(1000)
                    val newLst = file.lastModified()
                    if (newLst != lst)
                    {
                        logger.warning("检测到文件 ${file.name} 已被修改，程序将自动关闭")
                        shutdown(0, "File ${file.name} modified")
                    }
                }
            }
            catch (e: InterruptedException)
            {
                Thread.currentThread().interrupt()
            }
        }
        thread.isDaemon = true
        thread.start()
    }.onFailure()
    {
        logger.severe("启动监视器失败", it)
        shutdown(1, "Failed to start monitoring")
    }
}