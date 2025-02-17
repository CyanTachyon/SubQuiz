@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.quiz

import cn.org.subit.dataClass.*
import cn.org.subit.dataClass.QuizId.Companion.toQuizIdOrNull
import cn.org.subit.dataClass.SubjectId.Companion.toSubjectIdOrNull
import cn.org.subit.database.Histories
import cn.org.subit.database.Quizzes
import cn.org.subit.database.Sections
import cn.org.subit.plugin.rateLimit.RateLimit.NewQuiz
import cn.org.subit.route.utils.*
import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.statuses
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

fun Route.quiz() = route("/quiz", {
    tags("quiz")
})
{
    rateLimit(NewQuiz.rateLimitName)
    {
        post("/new", {
            summary = "开始新的测试"
            description = "开始新的测试, 若已有开始了但未完成的测试则会返回NotAcceptable以及未完成的测试的内容, 否则返回新测试的内容"
            request {
                queryParameter<Int>("count")
                {
                    required = true
                    description = "小测包含的题目数量"
                }
                queryParameter<SubjectId>("subject")
                {
                    required = false
                    description = "若该选项不为null, 则小测仅包含指定学科的题目"
                }
            }
            response {
                statuses<Quiz<Nothing?, Int?, Nothing?>>(HttpStatus.NotAcceptable.subStatus("已有未完成的测试"), HttpStatus.OK, example = Quiz.example.hideAnswer())
                statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest, HttpStatus.NotEnoughQuestions)
            }
        }, Context::newQuiz)
    }

    put<Map<SectionId, List<Int?>>>("/{id}/save", {
        summary = "保存测试"
        description = "保存测试的作答情况"
        request {
            pathParameter<QuizId>("id")
            {
                required = true
                description = "测试的id"
            }
            queryParameter<Boolean>("finish")
            {
                required = false
                description = "是否提交, 不填默认为false, 若为true, 则要求请求体中的所有选项不得为null"
            }
            body<Map<SectionId, List<Int?>>>()
            {
                required = true
                description = "选择的选项, `key`是section的id, value是一个list, 表示该section下的每个question的选项, null表示未作答"
                example("example", mapOf(SectionId(1) to listOf(0, 1, 2, 3), SectionId(2) to listOf(3, 2, 1, 0)))
            }
        }
        response {
            statuses(
                HttpStatus.OK,
                HttpStatus.BadRequest,
                HttpStatus.NotFound,
                HttpStatus.BadRequest.subStatus("作答情况与测试题目不匹配", 1),
                HttpStatus.BadRequest.subStatus("完成作答时, 仍有题目未完成", 2)
            )
        }
    }, Context::saveQuiz)

    get("/{id}/analysis", {
        summary = "获取测试分析"
        description = "获取已完成的测试的分析"

        request()
        {
            pathParameter<QuizId>("id")
            {
                required = true
                description = "测试的id"
            }
        }

        response()
        {
            statuses<Quiz<Int, Int, String>>(HttpStatus.OK, example = Quiz.example)
            statuses(HttpStatus.NotAcceptable.subStatus("该测试还未结束", 1), HttpStatus.NotFound)
        }
    }) { getAnalysis() }

    get("/histories", {
        summary = "获取测试历史记录"
        description = "获取测试历史记录"

        request()
        {
            paged()
        }

        response()
        {
            statuses<Slice<Quiz<Int?, Int?, String?>>>(HttpStatus.OK, example = sliceOf(Quiz.example))
        }
    }) { getHistories() }

}

private suspend fun Context.newQuiz(): Nothing
{
    val user = getLoginUser()?.id ?: finishCall(HttpStatus.Unauthorized)
    val subject = call.parameters["subject"]?.toSubjectIdOrNull()
    val quizzes: Quizzes = get()
    val q = quizzes.getUnfinishedQuiz(user)
    if (q != null) finishCall(HttpStatus.NotAcceptable.subStatus("已有未完成的测试"), q.hideAnswer())
    val count = call.parameters["count"]?.toIntOrNull() ?: finishCall(HttpStatus.BadRequest)
    val sections = get<Sections>().recommendSections(user, subject, count)
    if (sections.count < count) finishCall(HttpStatus.NotEnoughQuestions)
    finishCall(HttpStatus.OK, quizzes.addQuiz(user, sections.list).hideAnswer())
}

private suspend fun Context.saveQuiz(body: Map<SectionId, List<Int?>>): Nothing
{
    val user = getLoginUser()?.id ?: finishCall(HttpStatus.Unauthorized)
    val id = call.pathParameters["id"]?.toQuizIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val finish = call.parameters["finish"].toBoolean()
    val quizzes: Quizzes = get()
    val q = quizzes.getUnfinishedQuiz(user) ?: finishCall(HttpStatus.NotFound)
    if (q.id != id) finishCall(HttpStatus.NotFound)
    if (q.sections.size != body.size || !q.sections.map { it.id }.containsAll(body.keys) || !body.keys.containsAll(q.sections.map { it.id }))
        finishCall(HttpStatus.BadRequest.subStatus("作答情况与测试题目不匹配", 1))
    q.sections.forEach {
        if (body[it.id]?.size != it.questions.size)
            finishCall(HttpStatus.BadRequest.subStatus("作答情况与测试题目不匹配", 1))
    }
    val q1 = q.copy(finished = finish,sections = q.sections.map { section ->
        section.copy(questions = section.questions.mapIndexed { index, question ->
            question.copy(userAnswer = body[section.id]?.get(index))
        })
    })
    if (finish)
    {
        val q2 = q1.checkFinished() ?: finishCall(HttpStatus.BadRequest.subStatus("完成作答时, 仍有题目未完成", 2))
        quizzes.updateQuiz(q1.id, true, (Clock.System.now() - Instant.fromEpochMilliseconds(q1.time)).inWholeMilliseconds, q2.sections)
        get<Histories>().addHistories(user, q2.sections)
    }
    else
    {
        quizzes.updateQuiz(q1.id, false, null, q1.sections)
    }
    finishCall(HttpStatus.OK)
}

private suspend fun Context.getAnalysis(): Nothing
{
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val id = call.pathParameters["id"]?.toQuizIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val quizzes: Quizzes = get()
    val q = quizzes.getQuiz(id) ?: finishCall(HttpStatus.NotFound)
    if (q.user != user.id) finishCall(HttpStatus.NotFound)
    val finished = q.checkFinished() ?: finishCall(HttpStatus.NotAcceptable.subStatus("该测试还未结束", 1))
    finishCall(HttpStatus.OK, finished)
}

private suspend fun Context.getHistories(): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val (begin, count) = call.getPage()
    val quizzes: Quizzes = get()
    val qs = quizzes.getQuizzes(loginUser.id, begin, count).map { if (!it.finished) it.hideAnswer() else it }
    finishCall(HttpStatus.OK, qs)
}