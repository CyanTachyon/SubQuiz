@file:Suppress("PackageDirectoryMismatch")
@file:OptIn(ExperimentalTime::class)

package moe.tachyon.quiz.route.quiz

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.QuizId.Companion.toQuizIdOrNull
import moe.tachyon.quiz.database.*
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.rateLimit.RateLimit.NewQuiz
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.ai.AiGrading.checkAnswer
import moe.tachyon.quiz.utils.ai.TokenUsage
import moe.tachyon.quiz.utils.statuses
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ExperimentalTime

private val answerCheckingJobs = ConcurrentHashMap<QuizId, Job>()
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
                statuses<Quiz<Nothing?, Any?, Nothing?>>(HttpStatus.OK, example = Quiz.example.hideAnswer())
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
            body<Quiz<Any?, Any?, JsonElement?>>()
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

    get("/{id}", {
        summary = "获取测试"
        description = "若测试已完成, 则返回完整的测试内容, 否则返回隐藏了答案的测试内容. 若当前用户不是测试的创建者, 则需要有相应的权限"

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
            statuses<Quiz<Any?, Any?, JsonElement?>>(HttpStatus.OK, example = Quiz.example)
            statuses(HttpStatus.NotFound, HttpStatus.QuestionMarking)
        }
    }, Context::getQuiz)

    get("/unfinished", {
        summary = "获取未完成的测试"
        description = "获取未完成的测试, 若有多个则返回最近的一个"

        response()
        {
            statuses<List<Quiz<Nothing?, Any?, Nothing?>>>(HttpStatus.OK, example = listOf(Quiz.example.hideAnswer()))
            statuses(HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.QuestionMarking)
        }
    }, Context::getUnfinishedQuizzes)

    get("/histories", {
        summary = "获取测试历史记录"
        description = "获取测试历史记录"

        request()
        {
            paged()
        }

        response()
        {
            statuses<Slice<Quiz<Any?, Any?, JsonElement?>>>(HttpStatus.OK, example = sliceOf(Quiz.example))
        }
    }) { getHistories() }

}

private suspend fun Context.newQuiz(): Nothing
{
    val knowledgePoints = call.receiveNullable<List<KnowledgePointId>?>()
    val user = getLoginUser()?.id ?: finishCall(HttpStatus.Unauthorized)
    val quizzes: Quizzes = get()
    val count = call.parameters["count"]?.toIntOrNull() ?: finishCall(HttpStatus.BadRequest)
    val sections = get<Sections>().recommendSections(user, null, knowledgePoints, count)
    if (sections.count < count) finishCall(HttpStatus.NotEnoughQuestions)
    val quiz = quizzes.addQuiz(user, sections.list, null).hideAnswer()
    finishCall(HttpStatus.OK, quiz)
}

private suspend fun Context.saveQuiz(body: Quiz<Any?, Any?, JsonElement?>): Nothing
{
    val user = getLoginUser()?.id ?: finishCall(HttpStatus.Unauthorized)
    val id = call.pathParameters["id"]?.toQuizIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val finish = call.parameters["finish"].toBoolean()
    val quizzes: Quizzes = get()
    val q = quizzes.getUnfinishedQuizzes(user).firstOrNull { it.id == body.id } ?: finishCall(HttpStatus.NotFound)
    val q1 = runCatching { q.mergeUserAnswer(body) }.getOrElse { finishCall(HttpStatus.BadRequest.subStatus("作答情况与测试题目不匹配", 1)) }

    if (finish)
    {
        val job = SupervisorJob()
        if (answerCheckingJobs.putIfAbsent(id, job) != null)
        {
            job.complete()
            finishCall(HttpStatus.QuestionMarking)
        }
        job.invokeOnCompletion {
            answerCheckingJobs.remove(id)
        }
        val q2 = q1.copy(finished = true).checkFinished() ?: finishCall(HttpStatus.BadRequest.subStatus("完成作答时, 仍有题目未完成", 2))
        quizzes.updateQuiz(q1.id, true, (Clock.System.now() - Instant.fromEpochMilliseconds(q1.time)).inWholeMilliseconds, q2.sections)
        runCatching()
        {
            CoroutineScope(Dispatchers.IO + job).launch()
            {
                var totalToken = TokenUsage()
                val res = q2.sections
                    .map { async { it.checkAnswer() } }
                    .map {
                        val ans = it.await()
                        totalToken += ans.second
                        ans.first
                    }
                logger.severe("") { quizzes.updateQuizAnswerCorrect(q2.id, res, totalToken) }
                val score = (res zip q2.sections).associate { (r, s) ->
                    s.id to (r.count { it == true }.toDouble() / r.size)
                }
                logger.severe("") { get<Histories>().addHistories(user, score, q2.exam) }
                logger.severe("") { get<Users>().addTokenUsage(user, totalToken) }
                (res zip q2.sections)
                    .map { (r, s) -> s.type to (r.count { it == true }.toDouble() / r.size) }
                    .forEach {
                        logger.severe("")
                        {
                            val knowledgePoint =
                                get<SectionTypes>().getSectionType(it.first)?.knowledgePoint ?: return@forEach
                            get<Preferences>().addPreference(user, knowledgePoint, it.second)
                        }
                    }
            }
        }
        job.complete()
    }
    else
    {
        quizzes.updateQuiz(q1.id, false, null, q1.sections)
    }

    finishCall(HttpStatus.OK)
}

private suspend fun Context.getQuiz(): Nothing
{
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val id = call.pathParameters["id"]?.toQuizIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    if (answerCheckingJobs[id] != null) finishCall(HttpStatus.QuestionMarking)
    val quizzes: Quizzes = get()
    val q = quizzes.getQuiz(id) ?: finishCall(HttpStatus.NotFound)
    if (q.user != user.id)
    {
        if (q.exam == null) finishCall(HttpStatus.NotFound)
        val exams = get<Exams>()
        val exam = exams.getExam(q.exam) ?: finishCall(HttpStatus.NotFound)
        val classes = get<Classes>()
        val clazz = classes.getClassInfo(exam.clazz) ?: finishCall(HttpStatus.NotFound)
        val group = clazz.group
        if (!user.hasGlobalAdmin() && !get<Permissions>().getPermission(user.id, group).isAdmin())
            finishCall(HttpStatus.NotFound)
        if (clazz.withMembers().members.all { it.user != user.id })
            finishCall(HttpStatus.NotFound)
    }
    val finished = q.checkFinished()
    if (finished != null) finishCall(HttpStatus.OK, finished)
    else finishCall(HttpStatus.OK, q.hideAnswer())
}

private suspend fun Context.getUnfinishedQuizzes(): Nothing
{
    val user = getLoginUser()?.id ?: finishCall(HttpStatus.Unauthorized)
    val quizzes: Quizzes = get()
    val q = quizzes.getUnfinishedQuizzes(user).map { it.hideAnswer() }
    finishCall(HttpStatus.OK, q)
}

private suspend fun Context.getHistories(): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val (begin, count) = call.getPage()
    val quizzes: Quizzes = get()
    val qs = quizzes.getQuizzes(loginUser.id, begin, count)
        .map()
        {
            if (!it.finished) it.hideAnswer()
            else it
        }.map()
        {
            it.copy(correct = emptyList(), sections = it.sections.map()
            { section ->
                section.copy(
                    description = JsonPrimitive(""),
                    questions = listOf(
                        SingleChoiceQuestion(
                            description = JsonPrimitive(""),
                            options = emptyList(),
                            answer = null,
                            analysis = JsonPrimitive(""),
                            userAnswer = null,
                        )
                    )
                )
            })
        }
    finishCall(HttpStatus.OK, qs)
}