@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.plugin.webSockets

import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.websocket.*
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import kotlin.time.Duration.Companion.seconds

fun Application.installWebSockets() = install(WebSockets)
{
    pingPeriod = 15.seconds
    timeout = 15.seconds
    maxFrameSize = Long.MAX_VALUE
    masking = false
    contentConverter = KotlinxWebsocketSerializationConverter(contentNegotiationJson)
}