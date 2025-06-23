@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.plugin.doubleReceive

import io.ktor.server.application.*
import io.ktor.server.request.*

/**
 * 双接收插件, 一般在ktor中一个请求体只能被读取([ApplicationCall.receive])一次, 多次读取会抛出异常.
 *
 * 该插件可以让请求体被多次读取, 但是也会消耗更多的内存.
 *
 * 目前无多次接受的场景，因此暂时不启用，一般来说若RateLimit插件中需要读取请求体的话，就会导致请求体被读取两次，介时则需要使用该插件
 */
fun Application.installDoubleReceive() = Unit
/*
install(DoubleReceive)
{
    cacheRawRequest = true
    excludeFromCache { call, _ -> !call.request.contentType().match(ContentType.Application.Json) }
}
*/