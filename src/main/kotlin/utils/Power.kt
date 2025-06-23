package moe.tachyon.quiz.utils

import moe.tachyon.quiz.console.AnsiStyle.Companion.RESET
import moe.tachyon.quiz.console.SimpleAnsiColor.Companion.CYAN
import moe.tachyon.quiz.console.SimpleAnsiColor.Companion.PURPLE
import moe.tachyon.quiz.logger.SubQuizLogger
import io.ktor.server.application.*
import org.koin.core.component.KoinComponent
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
        if (this != null) runCatching()
        {
            monitor.raise(ApplicationStopPreparing, environment)
            engine.stop()
            this.dispose()
            logger.info("Ktor is stopped.")
        }.onFailure {
            logger.warning("Failed to stop Ktor: ${it.message}")
            it.printStackTrace(SubQuizLogger.err)
        }
        else logger.warning("Application is null")
        // 无论是否成功关闭, 都强制退出
        exitProcess(code)
    }
}