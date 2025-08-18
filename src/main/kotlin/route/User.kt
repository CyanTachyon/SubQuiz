@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.user

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.routing.*
import moe.tachyon.quiz.console.command.About
import moe.tachyon.quiz.console.command.About.Author
import moe.tachyon.quiz.dataClass.BasicUserInfo
import moe.tachyon.quiz.dataClass.Permission
import moe.tachyon.quiz.dataClass.UserFull
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.dataClass.UserId.Companion.toUserIdOrNull
import moe.tachyon.quiz.route.utils.finishCall
import moe.tachyon.quiz.route.utils.getLoginUser
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.SSO
import moe.tachyon.quiz.utils.statuses

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
        response()
        {
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
        response()
        {
            statuses<Author>(HttpStatus.OK, example = Author.example)
        }
    })
    {
        finishCall(HttpStatus.OK, About.author)
    }
}