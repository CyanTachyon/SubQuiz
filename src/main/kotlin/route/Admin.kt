@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.adimin

import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.PreparationGroupId.Companion.toPreparationGroupIdOrNull
import moe.tachyon.quiz.dataClass.UserId.Companion.toUserIdOrNull
import moe.tachyon.quiz.database.Permissions
import moe.tachyon.quiz.database.Users
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.SSO
import moe.tachyon.quiz.utils.statuses
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Route.admin() = route("/admin", {
    tags("admin")
})
{
    route("/group/{group}", {
        request()
        {
            pathParameter<PreparationGroupId>("group")
            {
                required = true
                description = "学科id"
            }
        }
    })
    {
        get("/list", {
            summary = "获取备课组管理员"
            description = "获取该备课组的管理员列表"
            request()
            {
                paged()
            }
            response()
            {
                statuses<Slice<UserId>>(HttpStatus.OK, example = sliceOf(UserId(1)))
            }
        })
        {
            val group = call.parameters["group"]?.toPreparationGroupIdOrNull() ?: finishCall(HttpStatus.BadRequest)
            val (begin, count) = call.getPage()
            val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
            val permissions: Permissions = get()
            if (loginUser.permission < Permission.ADMIN && permissions.getPermission(loginUser.id, group) < Permission.ADMIN)
                finishCall(HttpStatus.Forbidden)
            finishCall(HttpStatus.OK, permissions.getAdmins(group, begin, count).map { UserWithPermission(it.first, it.second) })
        }

        route("/user/{uid}", {
            request()
            {
                pathParameter<UserId>("uid")
                {
                    required = true
                    description = "用户id"
                }
            }
        })
        {
            get({
                summary = "获取用户对于某一备课组的权限"
                description = """
                    获取指定用户在指定备课组的权限
                """.trimIndent()
                response()
                {
                    statuses<Permission>(HttpStatus.OK)
                }
            })
            {
                val group = call.parameters["group"]?.toPreparationGroupIdOrNull() ?: finishCall(HttpStatus.BadRequest)
                val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
                val user = call.parameters["uid"]?.toUserIdOrNull()?.let { if (it == UserId(0)) loginUser.id else it } ?: finishCall(HttpStatus.BadRequest)
                val permissions: Permissions = get()
                if (user != loginUser.id && loginUser.permission < Permission.ADMIN && permissions.getPermission(loginUser.id, group) < Permission.ADMIN)
                    finishCall(HttpStatus.Forbidden)
                finishCall(HttpStatus.OK, permissions.getPermission(user, group))
            }

            put({
                summary = "修改用户对于某一备课组的权限"
                description = "修改指定用户在指定备课组的权限"
                request()
                {
                    body<Wrap<Permission>>()
                    {
                        required = true
                    }
                }
                response()
                {
                    statuses(HttpStatus.OK, HttpStatus.Forbidden, HttpStatus.Unauthorized)
                }
            })
            {
                val group = call.parameters["group"]?.toPreparationGroupIdOrNull() ?: finishCall(HttpStatus.BadRequest)
                val user = call.parameters["uid"]?.toUserIdOrNull() ?: finishCall(HttpStatus.BadRequest)
                val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
                val p = call.receive<Wrap<Permission>>().data
                val permissions: Permissions = get()
                val loginPermission = permissions.getPermission(loginUser.id, group)
                SSO.getDbUser(user) ?: finishCall(HttpStatus.NotFound)
                val userPermission = permissions.getPermission(user, group)

                if (!loginUser.hasGlobalAdmin() && loginPermission < Permission.ADMIN)
                    finishCall(HttpStatus.Forbidden)
                if (!loginUser.hasGlobalAdmin() && (loginPermission <= userPermission || loginPermission <= p) && loginUser.id != user)
                    finishCall(HttpStatus.Forbidden)
                permissions.setPermission(user, group, p)
                finishCall(HttpStatus.OK)
            }
        }

    }

    route("/global")
    {
        get("/list", {
            summary = "获取全局管理员"
            description = "获取全局管理员列表, 需要当前用户为全局管理员"
            request()
            {
                paged()
            }
            response()
            {
                statuses<Slice<UserWithPermission>>(HttpStatus.OK, example = sliceOf(UserWithPermission(UserId(1), Permission.ADMIN)))
            }
        })
        {
            val (begin, count) = call.getPage()
            val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
            if (loginUser.permission < Permission.ADMIN)
                finishCall(HttpStatus.Forbidden)
            val users: Users = get()
            val admins = users.getAdmins(begin, count).map { UserWithPermission(it.first, it.second) }
            finishCall(HttpStatus.OK, admins)
        }

        put("/user/{uid}", {
            summary = "修改用户全局权限"
            description = "修改指定用户的全局权限, 需要当前用户为全局管理员且对方修改前后权限都低于自己, 修正自己仅能向下修改"
            request()
            {
                pathParameter<UserId>("uid")
                {
                    required = true
                    description = "用户id, 0表示自己"
                }
                body<Wrap<Permission>>()
                {
                    required = true
                }
            }
            response()
            {
                statuses(HttpStatus.OK, HttpStatus.Forbidden, HttpStatus.Unauthorized)
            }
        })
        {
            val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
            val user = call.parameters["uid"]?.toUserIdOrNull()?.let { if (it == UserId(0)) loginUser.id else it } ?: finishCall(HttpStatus.BadRequest)
            val p = call.receive<Wrap<Permission>>().data
            val users: Users = get()
            val loginPermission = loginUser.permission
            val userPermission = SSO.getDbUser(user) ?: finishCall(HttpStatus.NotFound)
            if (loginPermission < Permission.ADMIN)
                finishCall(HttpStatus.Forbidden)
            if (loginUser.id != user && loginPermission <= maxOf(userPermission.permission, p))
                finishCall(HttpStatus.Forbidden)
            if (loginUser.id == user && loginPermission < p)
                finishCall(HttpStatus.Forbidden)
            users.changePermission(user, p)
            finishCall(HttpStatus.OK)
        }
    }
}

@Serializable
data class UserWithPermission(val user: UserId, val permission: Permission)