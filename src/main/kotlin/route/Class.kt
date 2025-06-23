@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.clazz

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.ClassId.Companion.toClassIdOrNull
import moe.tachyon.quiz.dataClass.PreparationGroupId.Companion.toPreparationGroupIdOrNull
import moe.tachyon.quiz.dataClass.UserId.Companion.toUserIdOrNull
import moe.tachyon.quiz.database.ClassMembers
import moe.tachyon.quiz.database.Classes
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.statuses

fun Route.clazz() = route("/class", {
    tags("class")
})
{
    get("/list", {
        summary = "获取班级列表"
        description = "获取指定用户的班级列表"
        request()
        {
            queryParameter<UserId>("userId")
            {
                required = false
                description = """
                    用户ID
                    
                    若不指定:
                    - 对于普通用户, 返回该用户所在的班级列表
                    - 对于管理员, 返回所有班级列表
                    
                    若指定:
                    - 对于普通用户, 无效
                    - 对于管理员, 返回指定用户所在的班级列表
                """.trimIndent()
            }
            queryParameter<PreparationGroupId>("group")
            {
                required = false
                description = "备课组ID, 如不指定则返回所有班级"
            }
            paged()
        }
        response()
        {
            statuses<Slice<ClassWithMembers>>(HttpStatus.OK, example = sliceOf(ClassWithMembers.example))
            statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
        }
    }, Context::getClassList)

    post("/create", {
        summary = "创建班级"
        description = "创建一个新的班级"
        request()
        {
            body<Class>
            {
                required = true
                description = "班级信息"
            }
        }
        response()
        {
            statuses<ClassId>(HttpStatus.Created, example = ClassId(1))
            statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest, HttpStatus.Conflict.subStatus("班级已存在"))
        }
    }, Context::createClass)

    put({
        summary = "更新班级信息"
        description = "更新指定班级的信息"
        request()
        {
            body<Class>
            {
                required = true
                description = "班级信息"
            }
        }
        response()
        {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest, HttpStatus.NotFound)
        }
    }, Context::updateClass)

    route("{id}", {
        request()
        {
            pathParameter<ClassId>("id")
            {
                required = true
                description = "班级ID"
            }
        }
    })
    {
        get({
            summary = "获取班级信息"
            description = "获取指定班级的信息"
            response()
            {
                statuses<ClassWithMembers>(HttpStatus.OK, example = ClassWithMembers.example)
                statuses(HttpStatus.Unauthorized, HttpStatus.NotFound)
            }
        }, Context::getClass)

        delete({
            summary = "删除班级"
            description = "删除指定班级"
            response()
            {
                statuses(HttpStatus.OK)
                statuses(HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.Forbidden)
            }
        }, Context::deleteClass)

        post("/members", {
            summary = "添加班级成员"
            description = "添加指定用户到班级"
            request()
            {
                body<List<SsoUserFull.Seiue>>
                {
                    required = true
                    description = "班级成员信息, 包含用户ID和角色"
                }
            }
            response()
            {
                statuses(HttpStatus.OK)
                statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest, HttpStatus.Conflict.subStatus("用户已在班级中"))
            }
        }, Context::addClassMember)

        delete("/members", {
            summary = "删除班级成员"
            description = "从班级中删除指定用户"
            request()
            {
                pathParameter<String>("studentId")
                {
                    required = true
                    description = "学号"
                }
            }
            response()
            {
                statuses(HttpStatus.OK)
                statuses(HttpStatus.Unauthorized, HttpStatus.NotFound, HttpStatus.Forbidden)
            }
        }, Context::removeClassMember)
    }
}

private suspend fun Context.getClassList(): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val userId = call.request.queryParameters["userId"]?.toUserIdOrNull()
    val group = call.request.queryParameters["group"]?.toPreparationGroupIdOrNull()
    val (begin, count) = call.getPage()
    val user = if (!loginUser.hasGlobalAdmin()) loginUser.id else userId
    val classes =
        if (user == null) get<Classes>().getClasses(group, begin, count)
        else get<ClassMembers>().getUserClasses(user, group, begin, count)
    val res = classes.map { it.withMembers() }
    finishCall(HttpStatus.OK, res)
}

private suspend fun Context.createClass(): Nothing
{
    val classData = call.receive<Class>()
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!loginUser.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    val clazz = get<Classes>().createClass(classData.name, classData.group) ?: finishCall(HttpStatus.Conflict, "班级已存在")
    finishCall(HttpStatus.OK, clazz)
}

private suspend fun Context.updateClass(): Nothing
{
    val classData = call.receive<Class>()
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!loginUser.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    val res = get<Classes>().updateClass(classData.id, classData.name, classData.group)
    if (res) finishCall(HttpStatus.OK)
    else finishCall(HttpStatus.Conflict)
}

private suspend fun Context.getClass(): Nothing
{
    val classId = call.parameters["id"]?.toClassIdOrNull() ?: finishCall(HttpStatus.BadRequest, "无效的班级ID")
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val clazz = get<Classes>().getClassInfo(classId)?.withMembers() ?: finishCall(HttpStatus.NotFound, "班级不存在")
    if (loginUser.hasGlobalAdmin() && clazz.members.any { it.user == loginUser.id })
        finishCall(HttpStatus.OK, clazz)
    else
        finishCall(HttpStatus.Forbidden, "无权访问该班级信息")
}

private suspend fun Context.deleteClass(): Nothing
{
    val classId = call.parameters["id"]?.toClassIdOrNull() ?: finishCall(HttpStatus.BadRequest, "无效的班级ID")
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!loginUser.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    val res = get<Classes>().deleteClass(classId)
    if (res) finishCall(HttpStatus.OK)
    else finishCall(HttpStatus.NotFound, "班级不存在")
}

private suspend fun Context.addClassMember(): Nothing
{
    val classId = call.parameters["id"]?.toClassIdOrNull() ?: finishCall(HttpStatus.BadRequest, "无效的班级ID")
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!loginUser.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    val memberData = call.receive<List<SsoUserFull.Seiue>>()
    val classMembers = get<ClassMembers>()
    classMembers.insertMembers(classId, memberData)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.removeClassMember(): Nothing
{
    val classId = call.parameters["id"]?.toClassIdOrNull() ?: finishCall(HttpStatus.BadRequest, "无效的班级ID")
    val studentId = call.queryParameters["studentId"] ?: finishCall(HttpStatus.BadRequest, "无效的用户ID")
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!loginUser.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    val classMembers = get<ClassMembers>()
    val res = classMembers.removeMembers(classId, listOf(studentId))
    if (res) finishCall(HttpStatus.OK)
    else finishCall(HttpStatus.NotFound, "班级或用户不存在")
}