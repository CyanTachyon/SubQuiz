@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.user

import cn.org.subit.console.command.About
import cn.org.subit.console.command.About.Author
import cn.org.subit.dataClass.BasicUserInfo
import cn.org.subit.dataClass.Permission
import cn.org.subit.dataClass.UserFull
import cn.org.subit.dataClass.UserId
import cn.org.subit.dataClass.UserId.Companion.toUserIdOrNull
import cn.org.subit.route.utils.finishCall
import cn.org.subit.route.utils.getLoginUser
import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.SSO
import cn.org.subit.utils.statuses
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Route.user() = route("user", {
    tags("user")
})
{
    get("/info/{id}", {
        summary = "获取用户信息"
        description = "获取用户信息"
        request()
        {
            pathParameter<Int>("id")
            {
                required = true
                description = "用户id, 0为当前用户"
            }
        }
        response {
            statuses<UserFull>(HttpStatus.OK.subStatus("获得完整信息成功", 0), example = UserFull.example)
            statuses<BasicUserInfo>(HttpStatus.OK.subStatus("获得基本信息成功", 1), example = BasicUserInfo.example)
        }
    })
    {
        val loginUser = getLoginUser()
        val id = call.pathParameters["id"]?.toUserIdOrNull() ?: finishCall(HttpStatus.BadRequest)
        if (id == UserId(0) || id == loginUser?.id)
            finishCall(HttpStatus.OK, loginUser ?: finishCall(HttpStatus.Unauthorized))

        val user = SSO.getUserFull(SSO.getAccessToken(id) ?: finishCall(HttpStatus.NotFound))
        if (user == null)
            finishCall(HttpStatus.NotFound)

        if (loginUser != null && loginUser.permission >= Permission.ADMIN)
            finishCall(HttpStatus.OK, user)

        finishCall(HttpStatus.OK, user.toBasicUserInfo())
    }

    get("/author", {
        summary = "获取作者信息"
        description = "获取作者信息"
        response {
            statuses<Author>(HttpStatus.OK, example = Author.example)
        }
    })
    {
        finishCall(HttpStatus.OK, About.author)
    }
}