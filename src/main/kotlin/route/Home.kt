@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.home

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.database.Histories
import moe.tachyon.quiz.route.utils.Context
import moe.tachyon.quiz.route.utils.finishCall
import moe.tachyon.quiz.route.utils.get
import moe.tachyon.quiz.route.utils.loginUser
import moe.tachyon.quiz.utils.HttpStatus
import kotlin.time.Duration.Companion.days

fun Route.home() = route("/home", {
    tags("home")
})
{
    // 获取过去30天做的题目数量
    get("/doneSectionCount", {
        summary = "获取过去30天做的题目数量"
        description = "获取过去30天做的题目数量"
    }, Context::getDoneSectionCount)
}

@Serializable
private data class DoneSectionCountResponse(
    val day: Int,
    val count: Int,
)

private suspend fun Context.getDoneSectionCount(): Nothing
{
    val counts = get<Histories>().getHistoryCount(loginUser.id, 30.days)
    val res: List<DoneSectionCountResponse> = counts.map { DoneSectionCountResponse(it.key, it.value) }
    finishCall(HttpStatus.OK, res)
}