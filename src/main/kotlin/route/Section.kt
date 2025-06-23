@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.section

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.KnowledgePointId.Companion.toKnowledgePointId
import moe.tachyon.quiz.dataClass.SectionId.Companion.toSectionIdOrNull
import moe.tachyon.quiz.dataClass.SectionTypeId.Companion.toSectionTypeIdOrNull
import moe.tachyon.quiz.database.KnowledgePoints
import moe.tachyon.quiz.database.Permissions
import moe.tachyon.quiz.database.SectionTypes
import moe.tachyon.quiz.database.Sections
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.COS
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.statuses
import moe.tachyon.quiz.utils.toEnumOrNull

fun Route.section() = route("/section", {
    tags("section")
})
{
    sectionType()

    post({
        summary = "创建题目"
        description = "创建一个Section"
        request()
        {
            body<Section<Any, Nothing?, String>>()
            {
                required = true
                description = "section信息, 其中的id将会被忽略"
                example("example", Section.example)
            }
        }
        response()
        {
            statuses<SectionId>(HttpStatus.OK, example = SectionId(1))
            statuses(HttpStatus.BadRequest, HttpStatus.BadRequest.subStatus("目标学科与section类型所在学科不符", 1))
        }
    }, Context::newSection)

    route("/{id}", {
        request()
        {
            pathParameter<SectionId>("id")
            {
                required = true
                description = "section id"
            }
        }
    })
    {
        get({
            summary = "获取题目"
            description = "获取一个Section"

            response()
            {
                statuses<Section<Any, Nothing?, String>>(HttpStatus.OK, example = Section.example.withoutUserAnswer())
            }
        }, Context::getSection)

        delete("", {
            summary = "删除题目"
            description = "删除一个Section"
            response()
            {
                statuses(HttpStatus.OK)
            }
        }, Context::deleteSection)

        sectionImage()
    }

    put("", {
        summary = "更新题目"
        description = "更新一个Section"
        request()
        {
            body<Section<Any, Nothing?, String>>()
            {
                required = true
                description = "section信息"
                example("example", Section.example)
            }
        }
        response()
        {
            statuses(HttpStatus.OK, HttpStatus.BadRequest.subStatus("目标学科与section类型所在学科不符", 1))
        }
    }, Context::updateSection)

    get("/list", {
        summary = "获取题目列表"
        description = "获取一个Section列表"
        request()
        {
            paged()
            queryParameter<KnowledgePointId>("knowledge")
            {
                required = true
                description = "知识点id"
            }
            queryParameter<SectionTypeId>("type")
            {
                required = false
                description = "section类型id"
            }
        }
        response()
        {
            statuses<Slice<Section<Any, Nothing?, String>>>(HttpStatus.OK, example = sliceOf(Section.example.withoutUserAnswer()))
        }
    }, Context::getSections)
}

private fun Route.sectionType() = route("/type", {})
{
    post({
        summary = "创建题目类型"
        description = "创建一个SectionType"
        request()
        {
            body<SectionType>()
            {
                required = true
                description = "section类型信息"
                example("example", SectionType.example)
            }
        }

        response()
        {
            statuses<SectionTypeId>(HttpStatus.OK, example = SectionTypeId(1))
        }
    }, Context::newSectionType)

    route("/{id}", {
        request()
        {
            pathParameter<SectionTypeId>("id")
            {
                required = true
                description = "section类型id"
            }
        }
    })
    {
        get({
            summary = "获取题目类型"
            description = "获取一个SectionType"
            response()
            {
                statuses<SectionType>(HttpStatus.OK, example = SectionType.example)
            }
        }, Context::getSectionType)

        delete("", {
            summary = "删除题目类型"
            description = "删除一个SectionType"
            response()
            {
                statuses(HttpStatus.OK)
            }
        }, Context::deleteSectionType)
    }

    put("", {
        summary = "更新题目类型"
        description = "更新一个SectionType"
        request()
        {
            body<SectionType>()
            {
                required = true
                description = "section类型信息"
                example("example", SectionType.example)
            }
        }
        response()
        {
            statuses(HttpStatus.OK)
        }
    }, Context::updateSectionType)

    get("/list", {
        summary = "获取题目类型列表"
        description = "获取一个SectionType列表"
        request()
        {
            queryParameter<KnowledgePointId>("knowledge")
            {
                required = true
                description = "知识点id"
            }
        }
        response()
        {
            statuses<List<SectionType>>(HttpStatus.OK, example = listOf(SectionType.example))
        }
    }, Context::getSectionTypes)
}

fun Route.sectionImage() = route("/image", {})
{
    post({
        request {
            queryParameter<ImageType>("type")
            {
                description = "图片的类型"
                required = true
                example(ImageType.PNG)
            }
            queryParameter<String>("md5")
            {
                description = "图片的md5值"
                required = true
            }
        }
        response {
            statuses<String>(HttpStatus.OK)
            statuses(
                HttpStatus.OK.subStatus(code = 1, message = "图片已存在"),
                HttpStatus.BadRequest.subStatus("md5 is required", 1),
                HttpStatus.BadRequest.subStatus("type is required", 2),
                HttpStatus.BadRequest.subStatus("section id is required", 3),
                HttpStatus.NotFound,
                HttpStatus.Unauthorized,
                HttpStatus.Forbidden
            )
        }
    }, Context::addSectionImage)

    delete({
        request()
        {
            queryParameter<String>("md5")
            {
                description = "图片的md5值"
                required = true
            }
        }
        response()
        {
            statuses(HttpStatus.OK)
            statuses(
                HttpStatus.BadRequest.subStatus("md5 is required", 1),
                HttpStatus.BadRequest.subStatus("section id is required", 3),
                HttpStatus.NotFound,
                HttpStatus.Unauthorized,
                HttpStatus.Forbidden
            )
        }
    }, Context::deleteSectionImage)

    get({
        request {
            queryParameter<SectionId>("id")
            {
                description = "section id"
                required = true
            }
        }
        response {
            statuses<List<String>>(HttpStatus.OK)
        }
    }, Context::getSectionImages)
}

private suspend fun Context.newSection(section: Section<Any, Nothing?, String>): Nothing
{
    if (section.questions.isEmpty() && section.available) finishCall(HttpStatus.BadRequest.subStatus("题目不能为空", 1))
    if (!section.check()) finishCall(HttpStatus.BadRequest.subStatus("题目不合法", 1))
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val sectionType = get<SectionTypes>().getSectionType(section.type) ?: finishCall(HttpStatus.NotFound)
    val kp = get<KnowledgePoints>().getKnowledgePoint(sectionType.knowledgePoint) ?: finishCall(HttpStatus.NotFound)

    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)

    val sections = get<Sections>()
    val res = sections.newSection(section)
    finishCall(HttpStatus.OK, res)
}

private suspend fun Context.getSection(): Nothing
{
    val id = call.parameters["id"]?.toSectionIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val section = get<Sections>().getSection(id) ?: finishCall(HttpStatus.NotFound)
    val sectionType = get<SectionTypes>().getSectionType(section.type) ?: finishCall(HttpStatus.NotFound)
    val kp = get<KnowledgePoints>().getKnowledgePoint(sectionType.knowledgePoint) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    finishCall(HttpStatus.OK, section)
}

private suspend fun Context.updateSection(section: Section<Any, Nothing?, String>): Nothing
{
    if (section.questions.isEmpty() && section.available) finishCall(HttpStatus.BadRequest.subStatus("题目不能为空", 1))
    if (!section.check()) finishCall(HttpStatus.BadRequest.subStatus("题目不合法", 1))
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val sectionType = get<SectionTypes>().getSectionType(section.type) ?: finishCall(HttpStatus.NotFound)
    val kp = get<KnowledgePoints>().getKnowledgePoint(sectionType.knowledgePoint) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val sections = get<Sections>()
    sections.updateSection(section)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.deleteSection(): Nothing
{
    val id = call.parameters["id"]?.toSectionIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val sections = get<Sections>()
    val section = sections.getSection(id) ?: finishCall(HttpStatus.NotFound)
    val sectionType = get<SectionTypes>().getSectionType(section.type) ?: finishCall(HttpStatus.NotFound)
    val kp = get<KnowledgePoints>().getKnowledgePoint(sectionType.knowledgePoint) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    sections.deleteSection(id)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.getSections(): Nothing
{
    val (begin, count) = call.getPage()
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val sections = get<Sections>()
    val kpId = call.request.queryParameters["knowledge"]?.toKnowledgePointId() ?: finishCall(HttpStatus.BadRequest.subStatus("知识点id不能为空", 1))
    val kp = get<KnowledgePoints>().getKnowledgePoint(kpId) ?: finishCall(HttpStatus.NotFound)
    val type = call.request.queryParameters["type"]?.toSectionTypeIdOrNull()
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    finishCall(HttpStatus.OK, sections.getSections(kpId, type, begin, count))
}

////////////////////////
///// SectionImage /////
////////////////////////

@Serializable
enum class ImageType(val contentType: ContentType)
{
    GIF(ContentType.Image.GIF),
    JPEG(ContentType.Image.JPEG),
    PNG(ContentType.Image.PNG),
    SVG(ContentType.Image.SVG),
    XIcon(ContentType.Image.XIcon),
}

private suspend fun Context.addSectionImage()
{
    val md5 = call.request.queryParameters["md5"] ?: finishCall(HttpStatus.BadRequest.subStatus("md5 is required", 1))
    val type = call.request.queryParameters["type"]?.toEnumOrNull<ImageType>() ?: finishCall(HttpStatus.BadRequest.subStatus("type is required", 2))
    val sectionId = call.parameters["id"]?.toSectionIdOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("section id is required", 3))
    val section = get<Sections>().getSection(sectionId) ?: finishCall(HttpStatus.NotFound)
    val sectionType = get<SectionTypes>().getSectionType(section.type) ?: finishCall(HttpStatus.NotFound)
    val kp = get<KnowledgePoints>().getKnowledgePoint(sectionType.knowledgePoint) ?: finishCall(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val res = COS.addImage(sectionId, md5, type.contentType) ?: finishCall(HttpStatus.OK.subStatus("图片已存在", 1))
    finishCall(HttpStatus.OK, res)
}

private suspend fun Context.deleteSectionImage()
{
    val md5 = call.request.queryParameters["md5"] ?: finishCall(HttpStatus.BadRequest.subStatus("md5 is required", 1))
    val sectionId = call.parameters["id"]?.toSectionIdOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("section id is required", 3))
    val section = get<Sections>().getSection(sectionId) ?: finishCall(HttpStatus.NotFound)
    val sectionType = get<SectionTypes>().getSectionType(section.type) ?: finishCall(HttpStatus.NotFound)
    val kp = get<KnowledgePoints>().getKnowledgePoint(sectionType.knowledgePoint) ?: finishCall(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    COS.removeImage(sectionId, md5)
    finishCall(HttpStatus.OK)
}

private fun Context.getSectionImages()
{
    val sectionId = call.parameters["id"]?.toSectionIdOrNull() ?: finishCall(HttpStatus.BadRequest.subStatus("section id is required", 1))
    finishCall(HttpStatus.OK, COS.getImages(sectionId))
}


///////////////////////
///// SectionType /////
///////////////////////

private suspend fun Context.newSectionType(sectionType: SectionType): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val kp = get<KnowledgePoints>().getKnowledgePoint(sectionType.knowledgePoint) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val sectionTypes = get<SectionTypes>()
    val id = sectionTypes.newSectionType(kp.id, sectionType.name) ?: finishCall(HttpStatus.Conflict.subStatus("存在同名的section type", 1))
    finishCall(HttpStatus.OK, id)
}

private suspend fun Context.getSectionType(): Nothing
{
    val id = call.parameters["id"]?.toSectionTypeIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val sectionType = get<SectionTypes>().getSectionType(id) ?: finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK, sectionType)
}

private suspend fun Context.updateSectionType(sectionType: SectionType): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val sectionTypes = get<SectionTypes>()
    val oldSectionType = sectionTypes.getSectionType(sectionType.id) ?: finishCall(HttpStatus.NotFound)
    val kp = get<KnowledgePoints>().getKnowledgePoint(oldSectionType.knowledgePoint) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    if (sectionType.knowledgePoint != oldSectionType.knowledgePoint)
    {
        val newKp = get<KnowledgePoints>().getKnowledgePoint(sectionType.knowledgePoint) ?: finishCall(HttpStatus.NotFound)
        if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, newKp.group) < Permission.ADMIN)
            finishCall(HttpStatus.Forbidden)
    }
    sectionTypes.updateSectionType(sectionType.id, sectionType.knowledgePoint, sectionType.name)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.deleteSectionType(): Nothing
{
    val id = call.parameters["id"]?.toSectionTypeIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val sectionTypes = get<SectionTypes>()
    val sectionType = sectionTypes.getSectionType(id) ?: finishCall(HttpStatus.NotFound)
    val kp = get<KnowledgePoints>().getKnowledgePoint(sectionType.knowledgePoint) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    sectionTypes.deleteSectionType(id)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.getSectionTypes(): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val sectionTypes = get<SectionTypes>()
    val kpId = call.request.queryParameters["knowledge"]?.toKnowledgePointId() ?: finishCall(HttpStatus.BadRequest.subStatus("知识点id不能为空", 1))
    val kp = get<KnowledgePoints>().getKnowledgePoint(kpId) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, kp.group) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    finishCall(HttpStatus.OK, sectionTypes.getSectionTypes(kp.id))
}