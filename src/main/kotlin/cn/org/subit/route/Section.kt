@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.section

import cn.org.subit.dataClass.*
import cn.org.subit.dataClass.SectionId.Companion.toSectionIdOrNull
import cn.org.subit.dataClass.SectionTypeId.Companion.toSectionTypeIdOrNull
import cn.org.subit.dataClass.SubjectId.Companion.toSubjectIdOrNull
import cn.org.subit.database.Permissions
import cn.org.subit.database.SectionTypes
import cn.org.subit.database.Sections
import cn.org.subit.route.utils.*
import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.statuses
import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

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
            body<SectionInfo<Int, Nothing?, String>>()
            {
                required = true
                description = "section信息"
                example("example", SectionInfo.example1)
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
                statuses<Section<Int, Nothing?, String>>(HttpStatus.OK, example = Section.example.withoutUserAnswer())
            }
        }, Context::getSection)

        put("", {
            summary = "更新题目"
            description = "更新一个Section"
            request()
            {
                body<SectionInfo<Int, Nothing?, String>>()
                {
                    required = true
                    description = "section信息"
                    example("example", SectionInfo.example1)
                }
            }
            response()
            {
                statuses(HttpStatus.OK, HttpStatus.BadRequest.subStatus("目标学科与section类型所在学科不符", 1))
            }
        }, Context::updateSection)

        delete("", {
            summary = "删除题目"
            description = "删除一个Section"
            response()
            {
                statuses(HttpStatus.OK)
            }
        }, Context::deleteSection)
    }

    get("/list", {
        summary = "获取题目列表"
        description = "获取一个Section列表"
        request()
        {
            paged()
            queryParameter<SubjectId>("subject")
            {
                required = false
                description = "学科id"
            }
            queryParameter<SectionTypeId>("type")
            {
                required = false
                description = "section类型id"
            }
        }
        response()
        {
            statuses<Slice<Section<Int, Nothing?, String>>>(HttpStatus.OK, example = sliceOf(Section.example.withoutUserAnswer()))
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
            body<SectionTypeInfo>()
            {
                required = true
                description = "section类型信息"
                example("example", SectionTypeInfo(SubjectId(1), "example", "example"))
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

        put("", {
            summary = "更新题目类型"
            description = "更新一个SectionType"
            request()
            {
                body<SectionTypeInfo>()
                {
                    required = true
                    description = "section类型信息"
                    example("example", SectionTypeInfo(SubjectId(1), "example", "example"))
                }
            }
            response()
            {
                statuses(HttpStatus.OK)
            }
        }, Context::updateSectionType)

        delete("", {
            summary = "删除题目类型"
            description = "删除一个SectionType"
            response()
            {
                statuses(HttpStatus.OK)
            }
        }, Context::deleteSectionType)
    }

    get("/list", {
        summary = "获取题目类型列表"
        description = "获取一个SectionType列表"
        request()
        {
            paged()
            queryParameter<SubjectId>("subject")
            {
                required = false
                description = "学科id"
            }
        }
        response()
        {
            statuses<Slice<SectionType>>(HttpStatus.OK, example = sliceOf(SectionType.example))
        }
    }, Context::getSectionTypes)
}

@Serializable
data class SectionInfo<out Answer: Int?, out UserAnswer: Int?, out Analysis: String?>(
    val subject: SubjectId,
    val type: SectionTypeId,
    val description: String,
    val questions: List<Question<Answer, UserAnswer, Analysis>>
)
{
    companion object
    {
        val example0 = SectionInfo(
            SubjectId(1),
            SectionTypeId(1),
            "this is a section",
            questions = listOf(Question.example)
        )
        val example1 = SectionInfo(
            SubjectId(1),
            SectionTypeId(1),
            "this is a section",
            questions = listOf(Question.example.withoutUserAnswer())
        )
    }
}

@Serializable
data class SectionTypeInfo(
    val subject: SubjectId,
    val name: String,
    val description: String,
)

private suspend fun Context.newSection(section: SectionInfo<Int, Nothing?, String>): Nothing
{
    if (section.questions.isEmpty()) finishCall(HttpStatus.BadRequest.subStatus("题目不能为空", 1))
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, section.subject) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val subject = get<SectionTypes>().getSectionType(section.type) ?: finishCall(HttpStatus.NotFound)
    if (subject.subject != section.subject) finishCall(HttpStatus.BadRequest.subStatus("目标学科与section类型所在学科不符", 1))
    val sections = get<Sections>()
    finishCall(HttpStatus.OK, sections.newSection(section.type, section.description, section.questions))
}

private suspend fun Context.getSection(): Nothing
{
    val id = call.parameters["id"]?.toSectionIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val section = get<Sections>().getSection(id) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, section.subject) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    finishCall(HttpStatus.OK, section)
}

private suspend fun Context.updateSection(section: SectionInfo<Int, Nothing?, String>): Nothing
{
    val id = call.parameters["id"]?.toSectionIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val subject = get<SectionTypes>().getSectionType(section.type) ?: finishCall(HttpStatus.NotFound)
    if (subject.subject != section.subject) finishCall(HttpStatus.BadRequest.subStatus("目标学科与section类型所在学科不符", 1))
    val sections = get<Sections>()
    val oldSection = sections.getSection(id) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, oldSection.subject) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    sections.updateSection(id, section.type, section.description, section.questions)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.deleteSection(): Nothing
{
    val id = call.parameters["id"]?.toSectionIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val sections = get<Sections>()
    val section = sections.getSection(id) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, section.subject) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    sections.deleteSection(id)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.getSections(): Nothing
{
    val (begin, count) = call.getPage()
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val permissions = get<Permissions>()
    val sections = get<Sections>()
    val subject = call.request.queryParameters["subject"]?.toSubjectIdOrNull()
    val type = call.request.queryParameters["type"]?.toSectionTypeIdOrNull()
    if (subject == null && type == null)
    {
        if (loginUser.permission < Permission.ADMIN) finishCall(HttpStatus.Forbidden)
    }
    else if (loginUser.permission < Permission.ADMIN)
    {
        val subject0 = subject ?: get<SectionTypes>().getSectionType(type!!)?.subject ?: finishCall(HttpStatus.NotFound)
        if (permissions.getPermission(loginUser.id, subject0) < Permission.ADMIN) finishCall(HttpStatus.Forbidden)
    }
    finishCall(HttpStatus.OK, sections.getSections(subject, type, begin, count))
}


private suspend fun Context.newSectionType(sectionType: SectionTypeInfo): Nothing
{
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, sectionType.subject) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    val sectionTypes = get<SectionTypes>()
    val id = sectionTypes.newSectionType(sectionType.subject, sectionType.name, sectionType.description) ?: finishCall(HttpStatus.Conflict.subStatus("存在同名的section type", 1))
    finishCall(HttpStatus.OK, id)
}

private suspend fun Context.getSectionType(): Nothing
{
    val id = call.parameters["id"]?.toSectionTypeIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val sectionType = get<SectionTypes>().getSectionType(id) ?: finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK, sectionType)
}

private suspend fun Context.updateSectionType(sectionType: SectionTypeInfo): Nothing
{
    val id = call.parameters["id"]?.toSectionTypeIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val sectionTypes = get<SectionTypes>()
    val oldSectionType = sectionTypes.getSectionType(id) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, oldSectionType.subject) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    sectionTypes.updateSectionType(id, sectionType.subject, sectionType.name, sectionType.description)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.deleteSectionType(): Nothing
{
    val id = call.parameters["id"]?.toSectionTypeIdOrNull() ?: finishCall(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
    val sectionTypes = get<SectionTypes>()
    val sectionType = sectionTypes.getSectionType(id) ?: finishCall(HttpStatus.NotFound)
    if (loginUser.permission < Permission.ADMIN && get<Permissions>().getPermission(loginUser.id, sectionType.subject) < Permission.ADMIN)
        finishCall(HttpStatus.Forbidden)
    sectionTypes.deleteSectionType(id)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.getSectionTypes(): Nothing
{
    val (begin, count) = call.getPage()
    val sectionTypes = get<SectionTypes>()
    val subject = call.request.queryParameters["subject"]?.toSubjectIdOrNull()
    finishCall(HttpStatus.OK, sectionTypes.getSectionTypes(subject, begin, count))
}