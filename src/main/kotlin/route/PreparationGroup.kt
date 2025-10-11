@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.preparationGroup

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.routing.*
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.PreparationGroupId.Companion.toPreparationGroupIdOrNull
import moe.tachyon.quiz.dataClass.SubjectId.Companion.toSubjectIdOrNull
import moe.tachyon.quiz.database.Permissions
import moe.tachyon.quiz.database.PreparationGroups
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.isWithinChineseCharLimit
import moe.tachyon.quiz.utils.statuses

fun Route.preparationGroup() = route("/preparationGroup", {
    tags("preparationGroup")
})
{
    get("/list", {
        description = "获取备课组列表"
        request()
        {
            queryParameter<SubjectId>("subject")
            {
                required = true
                description = "学科id"
            }
        }
        response()
        {
            statuses<List<PreparationGroup>>(HttpStatus.OK, example = listOf(PreparationGroup.example))
        }
    }, Context::getGroups)

    get("/{id}", {
        description = "获取备课组"
        request()
        {
            pathParameter<Int>("id")
            {
                required = true
                description = "备课组id"
            }
        }
        response()
        {
            statuses<PreparationGroup>(HttpStatus.OK, example = PreparationGroup.example)
            statuses(HttpStatus.NotFound)
        }
    }, Context::getGroup)

    put({
        description = "修改备课组"
        request()
        {
            body<PreparationGroup>()
            {
                required = true
                description = "备课组信息"
            }
        }
        response()
        {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.NotFound)
            statuses(HttpStatus.Conflict)
        }
    }, Context::updateGroup)

    post({
        description = "新建备课组"
        request()
        {
            body<PreparationGroup>()
            {
                required = true
                description = "备课组信息"
            }
        }
        response()
        {
            statuses<PreparationGroupId>(HttpStatus.OK, example = PreparationGroupId(1))
            statuses(HttpStatus.Conflict.subStatus("已有重名备课组", 1))
            statuses(HttpStatus.Forbidden)
        }
    }, Context::newGroup)
}

private suspend fun Context.getGroups(): Nothing
{
    val subjectId = call.parameters["subject"]?.toSubjectIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val groups = get<PreparationGroups>().getPreparationGroups(subjectId, loginUser)
    finishCall(HttpStatus.OK, groups)
}

private suspend fun Context.getGroup(): Nothing
{
    val id = call.parameters["id"]?.toPreparationGroupIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val preparationGroups = get<PreparationGroups>()
    if (!preparationGroups.hasPermission(id, loginUser)) finishCall(HttpStatus.Forbidden)
    val group = preparationGroups.getPreparationGroup(id) ?: finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK, group)
}

private suspend fun Context.updateGroup(preparationGroup: PreparationGroup): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!isWithinChineseCharLimit(preparationGroup.name, 8))
        finishCall(HttpStatus.BadRequest.subStatus("知识点名称过长", 1))
    if (!loginUser.hasGlobalAdmin() && get<Permissions>().getPermission(loginUser.id, preparationGroup.id) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val group = get<PreparationGroups>().getPreparationGroup(preparationGroup.id) ?: finishCall(HttpStatus.NotFound)
    if (group.subject != preparationGroup.subject)
        finishCall(HttpStatus.BadRequest.subStatus("备课组的学科不能修改", 1))
    if (group.name != preparationGroup.name && get<PreparationGroups>().getPreparationGroup(preparationGroup.subject, preparationGroup.name) != null)
        finishCall(HttpStatus.Conflict.subStatus("已有重名备课组", 1))
    get<PreparationGroups>().updatePreparationGroup(preparationGroup.id, preparationGroup.name, preparationGroup.description)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.newGroup(preparationGroup: PreparationGroup): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!isWithinChineseCharLimit(preparationGroup.name, 8))
        finishCall(HttpStatus.BadRequest.subStatus("知识点名称过长", 1))
    if (!loginUser.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    if (get<PreparationGroups>().getPreparationGroup(preparationGroup.subject, preparationGroup.name) != null)
        finishCall(HttpStatus.Conflict.subStatus("已有重名备课组", 1))
    val id = get<PreparationGroups>().newPreparationGroup(preparationGroup.subject, preparationGroup.name, preparationGroup.description)
    finishCall(HttpStatus.OK, id)
}