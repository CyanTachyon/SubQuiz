package cn.org.subit.utils

import cn.org.subit.logger.SubQuizLogger
import cn.org.subit.route.utils.example
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiResponse
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiResponses
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import me.nullaqua.api.kotlin.reflect.getField
import org.intellij.lang.annotations.Language

/**
 * 定义了一些出现的自定义的HTTP状态码, 更多HTTP状态码请参考[io.ktor.http.HttpStatusCode]
 */
@Suppress("unused")
data class HttpStatus(val code: HttpStatusCode, val message: String, val subStatus: Int = 0)
{
    companion object
    {
        val HttpStatusCode.Companion.ImATeapot: HttpStatusCode
            get() = HttpStatusCode(418, "I'm a teapot")
        val ImATeapot = HttpStatus(HttpStatusCode.ImATeapot, "抱歉，我是一只茶壶，无法为您泡咖啡！")
        // 操作成功 200
        val OK = HttpStatus(HttpStatusCode.OK, "操作成功")
        // 创建成功 201
        val Created = HttpStatus(HttpStatusCode.Created, "创建成功")
        // 邮箱格式错误 400
        val EmailFormatError = HttpStatus(HttpStatusCode.BadRequest, "邮箱格式错误")
        // 密码格式错误 400
        val PasswordFormatError = HttpStatus(HttpStatusCode.BadRequest, "密码格式错误")
        // 用户名格式错误 400
        val UsernameFormatError = HttpStatus(HttpStatusCode.BadRequest, "用户名格式错误")
        // 操作需要登陆, 未登陆 401
        val NotLoggedIn = HttpStatus(HttpStatusCode.Unauthorized, "未登录, 请先登录")
        // JWT Token 无效 401
        val InvalidToken = HttpStatus(HttpStatusCode.Unauthorized, "Token无效, 请重新登录")
        // OAuth code 无效 400
        val InvalidOAuthCode = HttpStatus(HttpStatusCode.BadRequest, "授权码无效")
        // 未授权 401
        val Unauthorized = HttpStatus(HttpStatusCode.Unauthorized, "未授权")
        // 密码错误 401
        val PasswordError = HttpStatus(HttpStatusCode.Unauthorized, "账户或密码错误")
        // 无法创建用户, 邮箱已被注册 406
        val EmailExist = HttpStatus(HttpStatusCode.NotAcceptable, "邮箱已被注册")
        // 不在白名单中 401
        val NotInWhitelist = HttpStatus(HttpStatusCode.Unauthorized, "不在白名单中, 请确认邮箱或联系管理员")
        // 账户不存在 404
        val AccountNotExist = HttpStatus(HttpStatusCode.NotFound, "账户不存在, 请先注册")
        // 越权操作 403
        val Forbidden = HttpStatus(HttpStatusCode.Forbidden, "权限不足")
        // 邮箱验证码错误 401
        val WrongEmailCode = HttpStatus(HttpStatusCode.Unauthorized, "邮箱验证码错误")
        // 未找到 404
        val NotFound = HttpStatus(HttpStatusCode.NotFound, "目标不存在或已失效")
        // 不合法的请求 400
        val BadRequest = HttpStatus(HttpStatusCode.BadRequest, "不合法的请求")
        // 服务器未知错误 500
        val InternalServerError = HttpStatus(HttpStatusCode.InternalServerError, "服务器未知错误")
        // 请求体过大 413
        val PayloadTooLarge = HttpStatus(HttpStatusCode.PayloadTooLarge, "请求体过大")
        // 不支持的媒体类型 415
        val UnsupportedMediaType = HttpStatus(HttpStatusCode.UnsupportedMediaType, "不支持的媒体类型")
        // 云文件存储空间已满 406
        val NotEnoughSpace = HttpStatus(HttpStatusCode.NotAcceptable, "云文件存储空间不足")
        // 账户被封禁
        val Prohibit = HttpStatus(HttpStatusCode.Unauthorized, "账户已被封禁, 如有疑问请联系管理员")
        // 包含违禁词汇
        val ContainsBannedWords = HttpStatus(HttpStatusCode.NotAcceptable, "包含违禁词汇")
        // 已拉黑
        val UserInBlackList = HttpStatus(HttpStatusCode.NotAcceptable, "对方已将拉黑")
        // 系统维护中
        val Maintaining = HttpStatus(HttpStatusCode.NotAcceptable, "系统维护中")
        // 发送验证码过于频繁
        val SendEmailCodeTooFrequent = HttpStatus(HttpStatusCode.TooManyRequests, "发送验证码过于频繁")
        // 请求过于频繁
        val TooManyRequests = HttpStatus(HttpStatusCode.TooManyRequests, "请求过于频繁")
        // 未实名
        val NotRealName = HttpStatus(HttpStatusCode(451, "Unavailable For Legal Reasons"), "未绑定实名信息")
        // 不接受
        val NotAcceptable = HttpStatus(HttpStatusCode.NotAcceptable, "不接受的请求")
        // 冲突
        val Conflict = HttpStatus(HttpStatusCode.Conflict, "冲突")
        // 登录成功但未授权
        val LoginSuccessButNotAuthorized = HttpStatus(HttpStatusCode.ExpectationFailed, "登录成功但未授权")
        // 没有足够多符合要求的题目
        val NotEnoughQuestions = HttpStatus(HttpStatusCode.RequestedRangeNotSatisfiable, "没有足够多符合要求的题目")
    }

    fun subStatus(message: String? = null, code: Int = this.subStatus) =
        if (message != null) HttpStatus(this@HttpStatus.code, "${this.message}: $message", code)
        else HttpStatus(this@HttpStatus.code, this.message, code)
}

@Serializable
data class Response<T>(val code: Int, val subStatus: Int, val message: String, val data: T)
{
    constructor(status: HttpStatus, data: T): this(status.code.value, status.subStatus, status.message, data)
}

suspend inline fun ApplicationCall.respond(status: HttpStatus) =
    this.respond(status.code, Response<Nothing?>(status, null))
suspend inline fun <reified T: Any> ApplicationCall.respond(status: HttpStatus, t: T) =
    this.respond(status.code, Response(status, t))

fun OpenApiResponses.statuses(vararg statuses: HttpStatus, @Language("Markdown") bodyDescription: String = "错误信息")
{
    @Suppress("UNCHECKED_CAST")
    val response = this@statuses.getField("responses") as Map<String, OpenApiResponse>
    val logger = SubQuizLogger.getLogger()

    statuses.forEach {
        if ("${it.code.value}/${it.subStatus}" in response) logger.warning("重复定义HTTP状态码: ${it.code.value}/${it.subStatus}", IllegalStateException())

        "${it.code.value}/${it.subStatus}" to {
            description = it.message
            body<Response<Nothing?>> {
                description = bodyDescription
                example("固定值", Response<Nothing?>(it, null))
            }
        }
    }
}

inline fun <reified T: Any> OpenApiResponses.statuses(
    vararg statuses: HttpStatus,
    @Language("Markdown")
    bodyDescription: String = "返回体",
    example: T
) = statuses<T>(*statuses, bodyDescription = bodyDescription, examples = listOf(example))

@JvmName("statusesWithBody")
inline fun <reified T: Any> OpenApiResponses.statuses(
    vararg statuses: HttpStatus,
    @Language("Markdown")
    bodyDescription: String = "返回体",
    examples: List<T> = emptyList()
)
{
    @Suppress("UNCHECKED_CAST")
    val response = this@statuses.getField("responses") as Map<String, OpenApiResponse>
    val logger = SubQuizLogger.getLogger()

    statuses.forEach {
        if ("${it.code.value}/${it.subStatus}" in response) logger.warning("重复定义HTTP状态码: ${it.code.value}/${it.subStatus}", IllegalStateException())
        "${it.code.value}/${it.subStatus}" to {
            description = it.message
            body<Response<T>>
            {
                description = bodyDescription
                if (examples.size == 1) example("example", Response(it, examples[0]))
                else examples.forEachIndexed { index, t -> example("example$index", Response(it, t)) }
            }
        }
    }
}

fun OpenApiResponses.statuses(contentType: ContentType, vararg statuses: HttpStatus, bodyDescription: String = "返回体") =
    statuses.forEach {
        it.message to {
            description = "code: ${it.code.value}, message: ${it.message}"
            body<Response<Nothing?>> {
                description = bodyDescription
                example("固定值", Response<Nothing?>(it, null))
                mediaTypes(contentType)
            }
        }
    }