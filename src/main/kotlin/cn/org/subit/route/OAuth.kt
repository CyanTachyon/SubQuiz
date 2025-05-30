@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.oauth

import cn.org.subit.logger.SubQuizLogger
import cn.org.subit.route.utils.finishCall
import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.SSO
import cn.org.subit.utils.statuses
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

private val logger = SubQuizLogger.getLogger()

fun Route.oauth() = route("oauth", {
    tags("OAuth")
})
{
    post("/login", {
        description = "通过sso登录"
        request {
            body<Login>()
            {
                description = "登录信息, code为oauth授权码"
                required = true
            }
        }
        response {
            statuses<String>(HttpStatus.OK, example = "token")
        }
    })
    {
        val login = call.receive<Login>()
        val accessToken = SSO.getAccessToken(login.code)
        if (accessToken == null)
        {
            logger.config("accessToken is null")
            finishCall(HttpStatus.InvalidOAuthCode)
        }
        val status = SSO.getStatus(accessToken)
        if (status == null)
        {
            logger.config("status is null")
            finishCall(HttpStatus.InvalidOAuthCode)
        }
        if (status != SSO.AuthorizationStatus.AUTHORIZED)
            finishCall(HttpStatus.Unauthorized)
        finishCall(HttpStatus.OK, accessToken)
    }
}

@Serializable
private data class Login(val code: String)