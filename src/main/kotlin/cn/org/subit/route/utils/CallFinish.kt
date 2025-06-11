@file:Suppress("NOTHING_TO_INLINE", "unused")

package cn.org.subit.route.utils

import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.respond
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class CallFinish(message: String, val block: suspend ApplicationCall.() -> Unit): RuntimeException(message)
{
    companion object
}

inline fun finishCall(httpStatus: HttpStatus): Nothing =
    throw CallFinish(httpStatus.toString()) { respond(httpStatus) }
inline fun <reified T: Any>finishCall(httpStatus: HttpStatus, body: T): Nothing =
    throw CallFinish(httpStatus.toString()) { respond<T>(httpStatus, body) }
inline fun finishCallWithText(httpStatus: HttpStatus, text: String, contentType: ContentType? = null): Nothing =
    throw CallFinish(httpStatus.toString()) { respondText(text, contentType, httpStatus.code) }
inline fun finishCallWithBytes(httpStatus: HttpStatus, contentType: ContentType, bytes: ByteArray): Nothing =
    throw CallFinish(httpStatus.toString()) { respondBytes(bytes, contentType, httpStatus.code) }
inline fun finishCallWithBytes(httpStatus: HttpStatus, contentType: ContentType, inputStream: InputStream): Nothing =
    throw CallFinish(httpStatus.toString()) { respondOutputStream(contentType, httpStatus.code) { inputStream.copyTo(this) } }
inline fun finishCallWithBytes(httpStatus: HttpStatus, contentType: ContentType, noinline producer: suspend OutputStream.() -> Unit): Nothing =
    throw CallFinish(httpStatus.toString()) { respondOutputStream(contentType, httpStatus.code, producer) }
inline fun finishCallWithFile(httpStatus: HttpStatus, contentType: ContentType, file: File): Nothing =
    throw CallFinish(httpStatus.toString()) { respondOutputStream(contentType, httpStatus.code, file.length()) { file.inputStream().copyTo(this) } }
inline fun finishCallWithRedirect(location: String): Nothing =
    throw CallFinish("redirect $location") { respondRedirect(location) }