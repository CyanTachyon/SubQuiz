@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.quiz

import cn.org.subit.dataClass.*
import cn.org.subit.dataClass.QuizId.Companion.toQuizIdOrNull
import cn.org.subit.database.*
import cn.org.subit.logger.SubQuizLogger
import cn.org.subit.plugin.rateLimit.RateLimit.NewQuiz
import cn.org.subit.route.utils.*
import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.Locks
import cn.org.subit.utils.ai.AI.checkAnswerAsync
import cn.org.subit.utils.ai.AiResponse
import cn.org.subit.utils.statuses
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.Collections.synchronizedMap

private val answerCheckingJobs = synchronizedMap(mutableMapOf<QuizId, Job>())
private val answerCheckingScope = CoroutineScope(Dispatchers.Default)
private val answerSubmitLock = Locks<UserId>()
private val logger = SubQuizLogger.getLogger()

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
                body<List<KnowledgePointId>>
                {
                    required = false
                    description = "知识点id列表, 若该选项不为null, 则小测仅包含指定知识点的题目"
                }
            }
            response {
                statuses<Quiz<Nothing?, Any?, Nothing?>>(HttpStatus.NotAcceptable.subStatus("已有未完成的测试"), HttpStatus.OK, example = Quiz.example.hideAnswer())
                statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest, HttpStatus.NotEnoughQuestions, HttpStatus.Conflict.subStatus("仍有测试在批阅中"))
            }
        }, Context::newQuiz)
    }

    put("/{id}/save", {
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
            body<Quiz<Any?, Any?, String?>>()
            {
                required = true
                description = "选择的选项, 仍以Quiz的形式传递，但该Quiz中仅userAnswer是重要的，其余值可任意填写"
                example("example", Quiz.example)
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
            statuses<Quiz<Any, Any, String>>(HttpStatus.OK, example = Quiz.example)
            statuses(HttpStatus.NotAcceptable.subStatus("该测试还未结束", 1), HttpStatus.NotFound, HttpStatus.QuestionMarking)
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
            statuses<Slice<Quiz<Any?, Any?, String?>>>(HttpStatus.OK, example = sliceOf(Quiz.example))
        }
    }) { getHistories() }

}

private suspend fun Context.newQuiz(): Nothing
{
    val knowledgePoints = call.receiveNullable<List<KnowledgePointId>?>()
    val user = getLoginUser()?.id ?: finishCall(HttpStatus.Unauthorized)
    val quizzes: Quizzes = get()
    answerSubmitLock.tryWithLock(user, { finishCall(HttpStatus.Conflict.subStatus("仍有测试在批阅中")) })
    {
        val q = quizzes.getUnfinishedQuiz(user)
        if (q != null) finishCall(HttpStatus.NotAcceptable.subStatus("已有未完成的测试"), q.hideAnswer())
        val count = call.parameters["count"]?.toIntOrNull() ?: finishCall(HttpStatus.BadRequest)
        val sections = get<Sections>().recommendSections(user, knowledgePoints, count)
        if (sections.count < count) finishCall(HttpStatus.NotEnoughQuestions)
        finishCall(HttpStatus.OK, quizzes.addQuiz(user, sections.list).hideAnswer())
    }
}

private suspend fun Context.saveQuiz(body: Quiz<Any?, Any?, String?>): Nothing
{
    val user = getLoginUser()?.id ?: finishCall(HttpStatus.Unauthorized)
    val id = call.pathParameters["id"]?.toQuizIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val finish = call.parameters["finish"].toBoolean()
    val quizzes: Quizzes = get()
    answerSubmitLock.withLock(user)
    {
        val q = quizzes.getUnfinishedQuiz(user) ?: finishCall(HttpStatus.NotFound)
        if (q.id != id) finishCall(HttpStatus.NotFound)
        val q1 = runCatching { q.mergeUserAnswer(body) }.getOrElse { finishCall(HttpStatus.BadRequest.subStatus("作答情况与测试题目不匹配", 1)) }
        if (finish)
        {
            val q2 = q1.copy(finished = true).checkFinished() ?: finishCall(HttpStatus.BadRequest.subStatus("完成作答时, 仍有题目未完成", 2))
            quizzes.updateQuiz(q1.id, true, (Clock.System.now() - Instant.fromEpochMilliseconds(q1.time)).inWholeMilliseconds, q2.sections)
            answerCheckingJobs[id] = answerCheckingScope.launch()
            {
                try
                {
                    answerSubmitLock.withLock(user)
                    {
                        var totalToken = AiResponse.Usage()
                        val res = q2.sections
                            .map { it.checkAnswerAsync() }
                            .map {
                                val ans = it.await()
                                totalToken += ans.second
                                ans.first
                            }
                        logger.severe("") { quizzes.updateQuizAnswerCorrect(q2.id, res, totalToken) }
                        val score = (res zip q2.sections).associate { (r, s) ->
                            s.id to (r.count { it == true }.toDouble() / r.size)
                        }
                        logger.severe("") { get<Histories>().addHistories(user, score) }
                        logger.severe("") { get<Users>().addTokenUsage(user, totalToken) }
                        (res zip q2.sections)
                            .map { (r, s) -> s.type to (r.count { it == true }.toDouble() / r.size) }
                            .forEach {
                                logger.severe("")
                                {
                                    val knowledgePoint = get<SectionTypes>().getSectionType(it.first)?.knowledgePoint ?: return@forEach
                                    get<Preferences>().addPreference(user, knowledgePoint, it.second)
                                }
                            }
                    }
                }
                finally
                {
                    answerCheckingJobs.remove(id)
                }
            }
        }
        else
        {
            quizzes.updateQuiz(q1.id, false, null, q1.sections)
        }
    }
    finishCall(HttpStatus.OK)
}

private suspend fun Context.getAnalysis(): Nothing
{
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val id = call.pathParameters["id"]?.toQuizIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    if (answerCheckingJobs[id] != null) finishCall(HttpStatus.QuestionMarking)
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