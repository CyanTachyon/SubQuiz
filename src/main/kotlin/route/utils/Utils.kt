@file:Suppress("unused", "NOTHING_TO_INLINE")

package moe.tachyon.quiz.route.utils

import io.github.smiley4.ktorswaggerui.data.ValueExampleDescriptor
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiRequest
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiRequestParameter
import io.github.smiley4.ktorswaggerui.dsl.routes.OpenApiSimpleBody
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.ClassId
import moe.tachyon.quiz.dataClass.PreparationGroupId
import moe.tachyon.quiz.dataClass.UserFull
import moe.tachyon.quiz.dataClass.hasGlobalAdmin
import moe.tachyon.quiz.database.ClassMembers
import moe.tachyon.quiz.database.Classes
import moe.tachyon.quiz.database.Permissions
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.getKoin
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier
import org.koin.ktor.ext.get

typealias Context = RoutingContext

context(context: Context)
inline fun <reified T: Any> get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null
) = context.call.application.get<T>(qualifier, parameters)

/**
 * 辅助方法, 标记此方法返回需要传入begin和count, 用于分页
 */
inline fun OpenApiRequest.paged()
{
    queryParameter<Long>("begin")
    {
        this.required = true
        this.description = "起始位置"
        this.example = ValueExampleDescriptor("example", 0)
    }
    queryParameter<Int>("count")
    {
        this.required = true
        this.description = "获取数量"
        this.example = ValueExampleDescriptor("example", 10)
    }
}

inline fun ApplicationCall.getPage(): Pair<Long, Int>
{
    val begin = request.queryParameters["begin"]?.toLongOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("begin is required"))
    val count = request.queryParameters["count"]?.toIntOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("count is required"))
    if (begin < 0 || count < 0) finishCall(HttpStatus.BadRequest.subStatus("begin and count must be non-negative"))
    return begin to count
}

inline fun <reified T> OpenApiSimpleBody.example(name: String, example: T)
{
    example(name) { value = example }
}

inline fun <reified T> OpenApiRequestParameter.example(any: T)
{
    this.example = ValueExampleDescriptor("example", any)
}

context(context: Context)
inline fun getLoginUser(): UserFull? = context.call.getLoginUser()
inline fun ApplicationCall.getLoginUser(): UserFull? = this.principal<UserFull>()

@get:JvmName("loginUser")
context(context: Context)
val loginUser: UserFull inline get() = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
@get:JvmName("loginUser")
val ApplicationCall.loginUser: UserFull inline get() = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)

inline fun ApplicationCall.getRealIp(): String
{
    val xForwardedFor = request.headers["X-Forwarded-For"]
    return if (xForwardedFor.isNullOrBlank()) request.local.remoteHost else xForwardedFor
}

@Serializable data class Wrap<T>(val data: T)

suspend inline fun UserFull.isAdminIn(group: PreparationGroupId): Boolean =
    hasGlobalAdmin() || getKoin().get<Permissions>().getPermission(this.id, group).isAdmin()
suspend inline fun UserFull.isAdminIn(clazz: ClassId): Boolean =
    isInClass(clazz) && (hasGlobalAdmin() || getKoin().get<Classes>().getClassInfo(clazz)?.let { isAdminIn(it.group) } == true)

suspend inline fun UserFull.isInClass(clazz: ClassId): Boolean =
    hasGlobalAdmin() || getKoin().get<ClassMembers>().getClassMembers(clazz).any { it.user == this.id }