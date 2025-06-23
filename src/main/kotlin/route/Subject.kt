@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.subject

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.Permission
import moe.tachyon.quiz.dataClass.Subject
import moe.tachyon.quiz.dataClass.SubjectId
import moe.tachyon.quiz.dataClass.SubjectId.Companion.toSubjectIdOrNull
import moe.tachyon.quiz.database.Subjects
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.statuses

fun Route.subject() = route("/subject", {
    tags("subject")
})
{
    post({
        summary = "新建学科"
        description = "新建一个学科, 需要全局管理员"
        request()
        {
            body<SubjectInfo>()
            {
                required = true
                description = "学科信息"
            }
        }

        response()
        {
            statuses<SubjectId>(HttpStatus.OK, example = SubjectId(1))
            statuses(HttpStatus.Conflict.subStatus("已有重名科目", 1))
        }
    }) { newSubject() }

    get("/{id}", {
        summary = "获得学科"
        description = "获得一个学科"
        request()
        {
            pathParameter<SubjectId>("id")
            {
                required = true
                description = "学科id"
            }
        }

        response()
        {
            statuses<Subject>(HttpStatus.OK, example = Subject.example)
        }
    }) { getSubject() }

    put("/{id}", {
        summary = "修改学科信息"
        description = "修改学科信息, 需要全局管理员或该科目的管理员"
        request()
        {
            body<SubjectInfo>()
            {
                required = true
                description = "学科信息"
            }
        }
        response()
        {
            statuses(HttpStatus.OK)
        }
    }) { editSubject() }

    get("/list", {
        summary = "获得学科列表"
        description = "获得学科列表"
        request()
        {
            paged()
        }
        response()
        {
            statuses<List<Subject>>(HttpStatus.OK, example = listOf(Subject.example))
        }
    }) { getSubjectList() }
}

@Serializable
private data class SubjectInfo(
    val name: String,
    val description: String,
)

private suspend fun Context.newSubject(): Nothing
{
    val user = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val subjects = get<Subjects>()
    if (user.permission < Permission.ADMIN) finishCall(HttpStatus.Forbidden)
    val body = call.receive<SubjectInfo>()
    val id = subjects.createSubject(body.name, body.description) ?: finishCall(HttpStatus.Conflict.subStatus("已有重名科目", 1))
    finishCall(HttpStatus.OK, id)
}

private suspend fun Context.getSubject(): Nothing
{
    val id = call.pathParameters["id"]?.toSubjectIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val subjects = get<Subjects>()
    val subject = subjects.getSubject(id) ?: finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK, subject)
}

private suspend fun Context.editSubject(): Nothing
{
    val id = call.pathParameters["id"]?.toSubjectIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (loginUser.permission < Permission.ADMIN) finishCall(HttpStatus.Forbidden)
    val body = call.receive<SubjectInfo>()
    get<Subjects>().updateSubject(id, body.name, body.description)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.getSubjectList(): Nothing
{
    val (begin, count) = call.getPage()
    val subjects = get<Subjects>()
    finishCall(HttpStatus.OK, subjects.getSubjects(begin, count))
}