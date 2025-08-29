@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.plugin.authentication

import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import moe.tachyon.quiz.config.apiDocsConfig
import moe.tachyon.quiz.dataClass.Permission
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.route.utils.finishCall
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.SSO

/**
 * 安装登陆验证服务
 */
fun Application.installAuthentication() = install(Authentication)
{
    val logger = SubQuizLogger.getLogger()
    // 此登陆仅用于api文档的访问, 见ApiDocs插件
    basic("auth-api-docs")
    {
        realm = "Access to the Swagger UI"
        validate()
        {
            if (it.name == apiDocsConfig.name && it.password == apiDocsConfig.password)
                UserIdPrincipal(it.name)
            else null
        }
    }

    bearer("auth")
    {
        authHeader()
        {
            val token = it.request.header(HttpHeaders.Authorization) ?: run()
            {
                val t = parseHeaderValue(it.request.header(HttpHeaders.SecWebSocketProtocol))
                val index = t.indexOfFirst { headerValue -> headerValue.value == "Bearer" }
                if (index == -1) return@run it.request.queryParameters["token"]?.let { token -> "Bearer $token" }
                it.response.header(HttpHeaders.SecWebSocketProtocol, "Bearer")
                t.getOrNull(index + 1)?.value?.let { token -> "Bearer $token" }
            }
            logger.config("request with token: $token")
            runCatching { token?.let(::parseAuthorizationHeader) }.getOrNull()
        }
        authenticate()
        {
            val token = it.token
            val status = SSO.getStatus(token)
            val user =
                if (status != SSO.AuthorizationStatus.AUTHORIZED) finishCall(HttpStatus.Unauthorized)
                else SSO.getUserFull(token)

            if (user == null) return@authenticate null

            if (user.permission < Permission.NORMAL) finishCall(HttpStatus.Prohibit)
            if (user.seiue.isEmpty()) //  || user.seiue.all(SsoUserFull.Seiue::archived)
                finishCall(HttpStatus.RealNameRequired, user)

            user
        }
    }
}