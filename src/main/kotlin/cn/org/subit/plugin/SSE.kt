@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.plugin.sse

import io.ktor.server.application.*
import io.ktor.server.sse.*

fun Application.installSSE() = install(SSE)