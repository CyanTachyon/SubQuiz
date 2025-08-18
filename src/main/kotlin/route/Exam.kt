@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.exam

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonElement
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.ClassId.Companion.toClassIdOrNull
import moe.tachyon.quiz.dataClass.ExamId.Companion.toExamIdOrNull
import moe.tachyon.quiz.database.*
import moe.tachyon.quiz.route.utils.Context
import moe.tachyon.quiz.route.utils.finishCall
import moe.tachyon.quiz.route.utils.get
import moe.tachyon.quiz.route.utils.getLoginUser
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.statuses

fun Route.exam() = route("/exam", {
    tags("exam")
})
{
    get("/class/{class}", {
        summary = "获取考试列表"
        description = "获取一个备课组的考试列表"
        request()
        {
            pathParameter<ClassId>("class")
            {
                required = true
                description = "备课组id"
            }
        }
        response()
        {
            statuses<List<Exam>>(HttpStatus.OK, example = listOf(Exam.example))
            statuses(HttpStatus.Forbidden, HttpStatus.BadRequest)
        }
    }, Context::getExams)

    post({
        summary = "新建考试"
        description = "新建一个考试"
        request()
        {
            body<Exam>()
            {
                required = true
                description = "考试信息"
            }
        }
        response()
        {
            statuses<ExamId>(HttpStatus.OK, example = ExamId(1))
            statuses(HttpStatus.Forbidden, HttpStatus.BadRequest, HttpStatus.Conflict)
        }
    }, Context::newExam)

    put({
        summary = "修改考试"
        description = "修改一个考试"
        request()
        {
            body<Exam>()
            {
                required = true
                description = "考试信息"
            }
        }
        response()
        {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.Forbidden, HttpStatus.BadRequest, HttpStatus.NotFound)
        }
    }, Context::updateExam)

    route("/{id}",{
        request()
        {
            pathParameter<ExamId>("id")
            {
                required = true
                description = "考试id"
            }
        }
    })
    {
        get({
            summary = "获取考试"
            description = "获取一个考试"
            response()
            {
                statuses<Exam>(HttpStatus.OK, example = Exam.example)
                statuses(HttpStatus.Forbidden, HttpStatus.NotFound)
            }
        }, Context::getExam)

        delete({
            summary = "删除考试"
            description = "删除一个考试"
            response()
            {
                statuses(HttpStatus.OK)
                statuses(HttpStatus.Forbidden, HttpStatus.NotFound)
            }
        }, Context::deleteExam)

        post("/start", {
            summary = "开始考试"
            description = "开始一个考试"
            response()
            {
                statuses<Quiz<Nothing?, Nothing?, Nothing?>>(HttpStatus.OK, example = Quiz.example.hideAnswer().withoutUserAnswer())
                statuses(HttpStatus.Unauthorized, HttpStatus.Forbidden, HttpStatus.BadRequest, HttpStatus.NotFound, HttpStatus.NotAcceptable.subStatus("已有未完成的测试"))
            }
        }, Context::startExam)

        get("/scores", {
            summary = "获取考试成绩"
            description = "获取一个考试的成绩列表"
            response()
            {
                statuses<List<Exams.ExamScore>>(HttpStatus.OK, example = listOf(Exams.ExamScore.example))
                statuses(HttpStatus.Forbidden, HttpStatus.NotFound)
            }
        }, Context::getExamScores)

        get("/student/{student}", {
            summary = "获取学生考试"
            description = "获取一个学生的考试结果"
            request()
            {
                pathParameter<String>("student")
                {
                    required = true
                    description = "学生学号"
                }
            }
            response()
            {
                statuses<Quiz<Any, Any, JsonElement>>(HttpStatus.OK, example = Quiz.example)
                statuses(HttpStatus.Forbidden, HttpStatus.NotFound, HttpStatus.NotAcceptable.subStatus("该学生未完成考试"))
            }
        }, Context::getStudentExam)
    }
}

private suspend fun Context.getExams(): Nothing
{
    val clazz = call.pathParameters["class"]?.toClassIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val group = get<Classes>().getClassInfo(clazz)?.group ?: finishCall(HttpStatus.NotFound)
    val admin = user.hasGlobalAdmin() || get<Permissions>().getPermission(user.id, group).isAdmin()
    val exams = get<Exams>().getExams(clazz)
    if (admin) finishCall(HttpStatus.OK, exams)
    else finishCall(HttpStatus.OK, exams.filter { it.available })
}

private suspend fun Context.getExam(): Nothing
{
    val id = call.pathParameters["id"]?.toExamIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val exam = get<Exams>().getExam(id) ?: finishCall(HttpStatus.NotFound)
    val clazz = get<Classes>().getClassInfo(exam.clazz) ?: finishCall(HttpStatus.NotFound)
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!user.hasGlobalAdmin() && !get<Permissions>().getPermission(user.id, clazz.group).isAdmin())
        finishCall(HttpStatus.Forbidden)
    finishCall(HttpStatus.OK, exam)
}

private suspend fun Context.deleteExam(): Nothing
{
    val id = call.pathParameters["id"]?.toExamIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val exam = get<Exams>().getExam(id) ?: finishCall(HttpStatus.NotFound)
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val clazz = get<Classes>().getClassInfo(exam.clazz) ?: finishCall(HttpStatus.NotFound)
    if (!user.hasGlobalAdmin() && !get<Permissions>().getPermission(user.id, clazz.group).isAdmin())
        finishCall(HttpStatus.Forbidden)
    if (get<Exams>().deleteExam(id)) finishCall(HttpStatus.OK)
    else finishCall(HttpStatus.NotFound)
}

private suspend fun Context.newExam(): Nothing
{
    val exam = call.receive<Exam>()
    val checked = exam.check()
    if (checked != null) finishCall(HttpStatus.BadRequest.subStatus(checked, 1))
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val clazz = get<Classes>().getClassInfo(exam.clazz) ?: finishCall(HttpStatus.NotFound)
    if (!user.hasGlobalAdmin() && !get<Permissions>().getPermission(user.id, clazz.group).isAdmin())
        finishCall(HttpStatus.Forbidden)
    val id = get<Exams>().addExam(exam) ?: finishCall(HttpStatus.Conflict)
    finishCall(HttpStatus.OK, id)
}

private suspend fun Context.updateExam(): Nothing
{
    val exam = call.receive<Exam>()
    val checked = exam.check()
    if (checked != null) finishCall(HttpStatus.BadRequest.subStatus(checked, 1))
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val clazz = get<Classes>().getClassInfo(exam.clazz) ?: finishCall(HttpStatus.NotFound)
    if (!user.hasGlobalAdmin() && !get<Permissions>().getPermission(user.id, clazz.group).isAdmin())
        finishCall(HttpStatus.Forbidden)
    if (get<Exams>().updateExam(exam)) finishCall(HttpStatus.OK)
    else finishCall(HttpStatus.NotFound)
}

private suspend fun Context.startExam()
{
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val examId = call.parameters["id"]?.toExamIdOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("exam id is required", 1))
    val exam = get<Exams>().getExam(examId) ?: finishCall(HttpStatus.NotFound)
    if (!exam.available) finishCall(HttpStatus.BadRequest.subStatus("考试不可用", 2))
    if (!get<ClassMembers>().inClass(user.id, exam.clazz))
        finishCall(HttpStatus.NotFound)
    val clazz = get<Classes>().getClassInfo(exam.clazz) ?: finishCall(HttpStatus.NotFound)
    if (user.hasGlobalAdmin() || get<Permissions>().getPermission(user.id, clazz.group).isAdmin())
        finishCall(HttpStatus.Forbidden.copy(message = "作为老师无法参与考试"))
    val sectionDB = get<Sections>()
    val sections = exam
        .sections
        .map { sectionDB.getSection(it) }
        .map { it ?: finishCall(HttpStatus.NotFound.subStatus("考试中的部分题目已失效，请联系管理员", 3)) }
    val quizzes: Quizzes = get()
    val history = quizzes.getQuiz(user.id, exam.id)
    if (history != null) finishCall(HttpStatus.OK, if (history.finished) history else history.hideAnswer())
    val quiz = quizzes.addQuiz(user.id, sections, exam.id)?.hideAnswer()
    if (quiz != null) finishCall(HttpStatus.OK, quiz)
    val unfinished = quizzes.getUnfinishedQuiz(user.id)!!.hideAnswer()
    if (unfinished.exam == examId) finishCall(HttpStatus.OK, unfinished)
    finishCall(HttpStatus.NotAcceptable.subStatus("已有未完成的测试", 1), unfinished)
}

private suspend fun Context.getExamScores(): Nothing
{
    val examId = call.pathParameters["id"]?.toExamIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val exam = get<Exams>().getExam(examId) ?: finishCall(HttpStatus.NotFound)
    val clazz = get<Classes>().getClassInfo(exam.clazz) ?: finishCall(HttpStatus.NotFound)
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!user.hasGlobalAdmin() && !get<Permissions>().getPermission(user.id, clazz.group).isAdmin())
        finishCall(HttpStatus.Forbidden)
    get<Exams>().getExamScores(examId)?.let {
        finishCall(HttpStatus.OK, it)
    }
    finishCall(HttpStatus.NotFound)
}

private suspend fun Context.getStudentExam()
{
    val examId = call.pathParameters["id"]?.toExamIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val studentId = call.parameters["student"] ?: finishCall(HttpStatus.BadRequest)
    val exam = get<Exams>().getExam(examId) ?: finishCall(HttpStatus.NotFound)
    val clazz = get<Classes>().getClassInfo(exam.clazz) ?: finishCall(HttpStatus.NotFound)
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!user.hasGlobalAdmin() && !get<Permissions>().getPermission(user.id, clazz.group).isAdmin())
        finishCall(HttpStatus.Forbidden)
    val userId = clazz.withMembers().members.find { it.seiue.studentId == studentId }?.user
        ?: finishCall(HttpStatus.NotFound.subStatus("未找到该学生", 1))
    val quizzes: Quizzes = get()
    val quiz = quizzes.getQuiz(userId, examId) ?: finishCall(HttpStatus.NotFound.subStatus("该学生未参加考试", 2))
    if (!quiz.finished) finishCall(HttpStatus.NotAcceptable.subStatus("该学生未完成考试", 3))
    finishCall(HttpStatus.OK, quiz)
}