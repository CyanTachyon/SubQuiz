@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.preparationGroup

import cn.org.subit.dataClass.Permission
import cn.org.subit.dataClass.PreparationGroup
import cn.org.subit.dataClass.PreparationGroupId
import cn.org.subit.dataClass.PreparationGroupId.Companion.toPreparationGroupIdOrNull
import cn.org.subit.dataClass.SubjectId
import cn.org.subit.dataClass.SubjectId.Companion.toSubjectIdOrNull
import cn.org.subit.dataClass.hasGlobalAdmin
import cn.org.subit.database.Permissions
import cn.org.subit.database.PreparationGroups
import cn.org.subit.route.utils.Context
import cn.org.subit.route.utils.finishCall
import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.statuses
import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.routing.Route
import cn.org.subit.route.utils.get
import cn.org.subit.route.utils.getLoginUser

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
    val groups = get<PreparationGroups>().getPreparationGroups(subjectId)
    finishCall(HttpStatus.OK, groups)
}

private suspend fun Context.getGroup(): Nothing
{
    val id = call.parameters["id"]?.toPreparationGroupIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val group = get<PreparationGroups>().getPreparationGroup(id) ?: finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK, group)
}

private suspend fun Context.updateGroup(preparationGroup: PreparationGroup): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!loginUser.hasGlobalAdmin() && get<Permissions>().getPermission(loginUser.id, preparationGroup.id) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val group = get<PreparationGroups>().getPreparationGroup(preparationGroup.id) ?: finishCall(HttpStatus.NotFound)
    if (group.subject != preparationGroup.subject)
        finishCall(HttpStatus.BadRequest.subStatus("备课组的学科不能修改", 1))
    if (group.name != preparationGroup.name && get<PreparationGroups>().getPreparationGroup(preparationGroup.name) != null)
        finishCall(HttpStatus.Conflict.subStatus("已有重名备课组", 1))
    get<PreparationGroups>().updatePreparationGroup(preparationGroup.id, preparationGroup.name, preparationGroup.description)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.newGroup(preparationGroup: PreparationGroup): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!loginUser.hasGlobalAdmin()) finishCall(HttpStatus.Forbidden)
    if (get<PreparationGroups>().getPreparationGroup(preparationGroup.name) != null)
        finishCall(HttpStatus.Conflict.subStatus("已有重名备课组", 1))
    val id = get<PreparationGroups>().newPreparationGroup(preparationGroup.subject, preparationGroup.name, preparationGroup.description)
    finishCall(HttpStatus.OK, id)
}