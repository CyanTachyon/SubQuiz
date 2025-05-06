@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.knowledgePoint

import cn.org.subit.dataClass.KnowledgePoint
import cn.org.subit.dataClass.KnowledgePointId
import cn.org.subit.dataClass.KnowledgePointId.Companion.toKnowledgePointIdOrNull
import cn.org.subit.dataClass.Permission
import cn.org.subit.dataClass.PreparationGroupId.Companion.toPreparationGroupIdOrNull
import cn.org.subit.dataClass.hasGlobalAdmin
import cn.org.subit.database.KnowledgePoints
import cn.org.subit.database.Permissions
import cn.org.subit.route.utils.Context
import cn.org.subit.route.utils.finishCall
import cn.org.subit.route.utils.get
import cn.org.subit.route.utils.getLoginUser
import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.statuses
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlin.collections.mutableListOf

fun Route.knowledgePoint() = route("/knowledgePoint", {
    tags("knowledgePoint")
})
{
    get("/list/{group}", {
        summary = "获取知识点列表"
        description = "获取一个备课组的知识点列表"
        request()
        {
            pathParameter<Int>("group")
            {
                required = true
                description = "备课组id"
            }
        }
        response()
        {
            statuses<List<KnowledgePointTree>>(HttpStatus.OK, example = listOf(KnowledgePointTree.example))
        }
    }, Context::getKnowledgePoints)

    get("/{id}", {
        summary = "获取知识点"
        description = "获取一个知识点"
        request()
        {
            pathParameter<KnowledgePointId>("id")
            {
                required = true
                description = "知识点id"
            }
        }
        response()
        {
            statuses<KnowledgePoint>(HttpStatus.OK, example = KnowledgePoint.example)
            statuses(HttpStatus.NotFound)
        }
    }, Context::getKnowledgePoint)

    post({
        summary = "新建知识点"
        description = "新建一个知识点, 需要管理员权限"
        request()
        {
            body<KnowledgePoint>()
            {
                required = true
                description = "知识点信息"
            }
        }
        response()
        {
            statuses<KnowledgePointId>(HttpStatus.OK, example = KnowledgePointId(1))
            statuses(
                HttpStatus.NotFound,
                HttpStatus.Forbidden,
                HttpStatus.Conflict.subStatus("已有重名知识点", 1),
                HttpStatus.BadRequest.subStatus("父知识点不属于该备课组", 1),
                HttpStatus.BadRequest.subStatus("父知识点不是文件夹", 2),
            )

        }
    }, Context::newKnowledgePoint)

    put({
        summary = "更新知识点"
        description = "更新一个知识点, 需要管理员权限"
        request()
        {
            body<KnowledgePoint>()
            {
                required = true
                description = "知识点信息"
            }
        }
        response()
        {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.NotFound)
        }
    }, Context::updateKnowledgePoint)


}

@Serializable
private data class KnowledgePointTree(
    val info: KnowledgePoint,
    val children: List<KnowledgePointTree>,
)
{
    companion object
    {
        fun makeTree(knowledgePoints: List<KnowledgePoint>, ): List<KnowledgePointTree>
        {
            val map = mutableMapOf<KnowledgePointId?, MutableList<KnowledgePointTree>>()
            for (kp in knowledgePoints) map[kp.id] = mutableListOf()
            map[null] = mutableListOf()
            for (kp in knowledgePoints)
                map[kp.father]?.add(KnowledgePointTree(kp, map[kp.id]!!))
            return map[null]!!
        }

        val example = KnowledgePointTree(
            KnowledgePoint.example,
            listOf(
                KnowledgePointTree(KnowledgePoint.example, emptyList()),
                KnowledgePointTree(KnowledgePoint.example, emptyList()),
            ),
        )
    }
}

private suspend fun Context.getKnowledgePoints(): Nothing
{
    val group = call.parameters["group"]?.toPreparationGroupIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val kps = get<KnowledgePoints>().getKnowledgePoints(group)
    finishCall(HttpStatus.OK, KnowledgePointTree.makeTree(kps))
}

private suspend fun Context.getKnowledgePoint(): Nothing
{
    val id = call.parameters["id"]?.toKnowledgePointIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val knowledgePoint = get<KnowledgePoints>().getKnowledgePoint(id) ?: finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK, knowledgePoint)
}

private suspend fun Context.newKnowledgePoint(knowledgePoint: KnowledgePoint): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!loginUser.hasGlobalAdmin() && get<Permissions>().getPermission(loginUser.id, knowledgePoint.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val knowledgePoints = get<KnowledgePoints>()
    val father = knowledgePoint.father?.let { knowledgePoints.getKnowledgePoint(it) ?: finishCall(HttpStatus.NotFound) }
    if (father != null && father.group != knowledgePoint.group)
        finishCall(HttpStatus.BadRequest.subStatus("父知识点不属于该备课组", 1))
    if (father != null && !father.folder)
        finishCall(HttpStatus.BadRequest.subStatus("父知识点不是文件夹", 2))
    val id = knowledgePoints.addKnowledgePoint(
        knowledgePoint.group,
        knowledgePoint.name,
        knowledgePoint.folder,
        knowledgePoint.father,
    ) ?: finishCall(HttpStatus.Conflict.subStatus("已有重名知识点", 1))
    finishCall(HttpStatus.OK, id)
}

private suspend fun Context.updateKnowledgePoint(knowledgePoint: KnowledgePoint): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (!loginUser.hasGlobalAdmin() && get<Permissions>().getPermission(loginUser.id, knowledgePoint.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val knowledgePoints = get<KnowledgePoints>()
    val oldKnowledgePoint = knowledgePoints.getKnowledgePoint(knowledgePoint.id) ?: finishCall(HttpStatus.NotFound)
    if (oldKnowledgePoint.group != knowledgePoint.group)
        finishCall(HttpStatus.BadRequest.subStatus("不能修改知识点的备课组", 1))
    if (oldKnowledgePoint.folder != knowledgePoint.folder)
        finishCall(HttpStatus.BadRequest.subStatus("不能修改知识点的文件夹属性", 2))
    if (oldKnowledgePoint.father != knowledgePoint.father && knowledgePoint.father != null)
    {
        val father = knowledgePoints.getKnowledgePoint(knowledgePoint.father) ?: finishCall(HttpStatus.NotFound)
        if (father.group != knowledgePoint.group)
            finishCall(HttpStatus.BadRequest.subStatus("父知识点不属于该备课组", 1))
        if (!father.folder)
            finishCall(HttpStatus.BadRequest.subStatus("父知识点不是文件夹", 2))
    }
    knowledgePoints.updateKnowledgePoint(
        knowledgePoint.id,
        knowledgePoint.name,
        knowledgePoint.folder,
        knowledgePoint.father,
    )
    finishCall(HttpStatus.OK)
}