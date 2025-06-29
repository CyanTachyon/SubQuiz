@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.terminal

import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.Loader
import moe.tachyon.quiz.config.loggerConfig
import moe.tachyon.quiz.console.command.CommandSender
import moe.tachyon.quiz.console.command.invokeTabCompleteToStrings
import moe.tachyon.quiz.dataClass.Permission
import moe.tachyon.quiz.dataClass.UserFull
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.logger.ToConsoleHandler
import moe.tachyon.quiz.route.terminal.Type.*
import java.util.logging.Handler
import java.util.logging.LogRecord

private val logger = SubQuizLogger.getLogger()

private val loggerFlow = MutableSharedFlow<Packet<String>>(
    replay = 100,
    extraBufferCapacity = 100,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
private val sharedFlow = loggerFlow.asSharedFlow()

private val init: Unit by lazy()
{
    SubQuizLogger.globalLogger.logger.addHandler(object: Handler()
    {
        init
        {
            this.formatter = ToConsoleHandler.formatter
        }

        override fun publish(record: LogRecord)
        {
            if (!loggerConfig.check(record)) return
            if (record.loggerName.startsWith("io.ktor.websocket")) return
            loggerFlow.tryEmit(Packet(MESSAGE, formatter.format(record)))
        }

        override fun flush() = Unit
        override fun close() = Unit
    })
}

fun Route.terminal() = route("/terminal", {
    hidden = true
})
{
    init
    webSocket("/api")
    {
        val loginUser = call.principal<UserFull>()
        if (loginUser == null || loginUser.permission != Permission.ROOT)
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Permission denied"))

        val job = launch { sharedFlow.collect(::sendSerialized) }

        class WebSocketCommandSender(user: UserFull): CommandSender("WebSocket('${user.username}' (id: ${user.id}))")
        {
            override suspend fun out(line: String) = sendSerialized(Packet(MESSAGE, parseLine(line, false)))
            override suspend fun err(line: String) = sendSerialized(Packet(MESSAGE, parseLine(line, true)))
            override suspend fun clear() = sendSerialized(Packet(CLEAR, null))
        }

        val sender = WebSocketCommandSender(loginUser)

        runCatching {
            while (true)
            {
                val packet = receiveDeserialized<Packet<String>>()
                when (packet.type)
                {
                    COMMAND -> sender.invokeCommand(packet.data)
                    TAB -> sendSerialized(Packet(TAB, sender.invokeTabCompleteToStrings(packet.data)))
                    MESSAGE, CLEAR -> Unit
                }
            }
        }.onFailure { exception ->
            logger.info("WebSocket exception: ${exception.localizedMessage}")
        }.also {
            job.cancel()
        }
    }

    get("")
    {
        val html = Loader.getResource("terminal.html")!!
            .readAllBytes()
            .decodeToString()
            .replace("\${root}", application.rootPath)
        call.respondBytes(html.toByteArray(), ContentType.Text.Html, HttpStatusCode.OK)
    }

    get("/icon")
    {
        call.respondBytes(
            Loader.getResource("logo/SubIT-icon.png")!!.readAllBytes(),
            ContentType.Image.PNG,
            HttpStatusCode.OK
        )
    }
}

@Serializable
private enum class Type
{
    // request
    COMMAND,

    // request & response
    TAB,

    // response
    MESSAGE,

    // response
    CLEAR,
}

@Serializable
private data class Packet<T>(val type: Type, val data: T)