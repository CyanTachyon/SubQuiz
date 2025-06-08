@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.exam

import cn.org.subit.dataClass.*
import cn.org.subit.dataClass.ExamId.Companion.toExamIdOrNull
import cn.org.subit.dataClass.PreparationGroupId.Companion.toPreparationGroupIdOrNull
import cn.org.subit.database.Exams
import cn.org.subit.database.Permissions
import cn.org.subit.database.PreparationGroups
import cn.org.subit.route.section.ImageType
import cn.org.subit.route.utils.Context
import cn.org.subit.route.utils.finishCall
import cn.org.subit.route.utils.get
import cn.org.subit.route.utils.getLoginUser
import cn.org.subit.utils.COS
import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.statuses
import cn.org.subit.utils.toEnumOrNull
import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Route.exam() = route("/exam", {
    tags("exam")
})
{
    get("/group/{group}", {
        summary = "获取考试列表"
        description = "获取一个备课组的考试列表"
        request()
        {
            pathParameter<PreparationGroupId>("group")
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

        route("/image")
        {
            post({
                summary = "添加考试图片"
                description = "添加考试图片"
                request()
                {
                    queryParameter<String>("md5")
                    {
                        required = true
                        description = "图片md5"
                    }
                    queryParameter<ImageType>("type")
                    {
                        required = true
                        description = "图片类型"
                    }
                }
                response()
                {
                    statuses(HttpStatus.OK)
                    statuses(HttpStatus.Forbidden, HttpStatus.BadRequest)
                }
            }, Context::addSectionImage)

            delete({
                summary = "删除考试图片"
                description = "删除考试图片"
                request()
                {
                    queryParameter<String>("md5")
                    {
                        required = true
                        description = "图片md5"
                    }
                }
                response()
                {
                    statuses(HttpStatus.OK)
                    statuses(HttpStatus.Forbidden, HttpStatus.BadRequest)
                }
            }, Context::deleteSectionImage)

            get({
                summary = "获取考试图片"
                description = "获取考试图片"
                response()
                {
                    statuses<List<String>>(HttpStatus.OK)
                    statuses(HttpStatus.Forbidden, HttpStatus.BadRequest)
                }
            }, Context::getSectionImages)
        }
    }
}

private suspend fun Context.getExams(): Nothing
{
    val group = call.pathParameters["group"]?.toPreparationGroupIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val admin = user.hasGlobalAdmin() || get<Permissions>().getPermission(user.id, group) >= Permission.ADMIN
    val exams = get<Exams>().getExams(group)
    if (admin) finishCall(HttpStatus.OK, exams.map(Exam::convertSectionIds))
    else
    {
        val emptySection = Section<Any, Nothing?, String>(
            id = SectionId(0),
            type = SectionTypeId(0),
            description = "",
            weight = 0,
            available = false,
            markdown = false,
            questions = emptyList(),
        )
        finishCall(HttpStatus.OK, exams.filter { it.available }.map { it.copy(sections = it.sections.map { emptySection }) }.map(Exam::convertSectionIds))
    }
}

private suspend fun Context.getExam(): Nothing
{
    val id = call.pathParameters["id"]?.toExamIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val exam = get<Exams>().getExam(id) ?: finishCall(HttpStatus.NotFound)
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!user.hasGlobalAdmin() && get<Permissions>().getPermission(user.id, exam.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    finishCall(HttpStatus.OK, exam.convertSectionIds())
}

private suspend fun Context.newExam(): Nothing
{
    val exam = call.receive<Exam>()
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val group = get<PreparationGroups>().getPreparationGroup(exam.group) ?: finishCall(HttpStatus.NotFound)
    if (!user.hasGlobalAdmin() && get<Permissions>().getPermission(user.id, group.id) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val id = get<Exams>().addExam(exam) ?: finishCall(HttpStatus.Conflict)
    finishCall(HttpStatus.OK, id)
}

private suspend fun Context.updateExam(): Nothing
{
    val exam = call.receive<Exam>()
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val group = get<PreparationGroups>().getPreparationGroup(exam.group) ?: finishCall(HttpStatus.NotFound)
    if (!user.hasGlobalAdmin() && get<Permissions>().getPermission(user.id, group.id) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    if (get<Exams>().updateExam(exam)) finishCall(HttpStatus.OK)
    else finishCall(HttpStatus.NotFound)
}

private suspend fun Context.addSectionImage()
{
    val md5 = call.request.queryParameters["md5"] ?: finishCall(HttpStatus.BadRequest.subStatus("md5 is required", 1))
    val type = call.request.queryParameters["type"]?.toEnumOrNull<ImageType>() ?: finishCall(HttpStatus.BadRequest.subStatus("type is required", 2))
    val examId = call.parameters["id"]?.toExamIdOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("exam id is required", 3))
    val exam = get<Exams>().getExam(examId) ?: finishCall(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, exam.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val res = COS.addImage(examId, md5, type.contentType) ?: finishCall(HttpStatus.OK.subStatus("图片已存在", 1))
    finishCall(HttpStatus.OK, res)
}

private suspend fun Context.deleteSectionImage()
{
    val md5 = call.request.queryParameters["md5"] ?: finishCall(HttpStatus.BadRequest.subStatus("md5 is required", 1))
    val examId = call.parameters["id"]?.toExamIdOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("exam id is required", 2))
    val exam = get<Exams>().getExam(examId) ?: finishCall(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, exam.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    COS.removeImage(examId, md5)
    finishCall(HttpStatus.OK)
}

private fun Context.getSectionImages()
{
    val examId = call.parameters["id"]?.toExamIdOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("exam id is required", 1))
    finishCall(HttpStatus.OK, COS.getImages(examId))
}