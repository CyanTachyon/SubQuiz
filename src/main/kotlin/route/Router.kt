package moe.tachyon.quiz.route

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import moe.tachyon.quiz.config.systemConfig
import moe.tachyon.quiz.route.adimin.admin
import moe.tachyon.quiz.route.ai.ai
import moe.tachyon.quiz.route.clazz.clazz
import moe.tachyon.quiz.route.exam.exam
import moe.tachyon.quiz.route.home.home
import moe.tachyon.quiz.route.knowledgePoint.knowledgePoint
import moe.tachyon.quiz.route.oauth.oauth
import moe.tachyon.quiz.route.parctice.practice
import moe.tachyon.quiz.route.preparationGroup.preparationGroup
import moe.tachyon.quiz.route.quiz.quiz
import moe.tachyon.quiz.route.section.section
import moe.tachyon.quiz.route.subject.subject
import moe.tachyon.quiz.route.terminal.terminal
import moe.tachyon.quiz.route.user.user
import moe.tachyon.quiz.route.utils.finishCall
import moe.tachyon.quiz.utils.HttpStatus

fun Application.router() = routing()
{
    val rootPath = this.application.rootPath

    get("/", { hidden = true })
    {
        call.respondRedirect("$rootPath/api-docs/index.html")
    }

    authenticate("auth-api-docs")
    {
        route("/api-docs")
        {
            route("/api.json")
            {
                openApiSpec()
            }
            swaggerUI("$rootPath/api-docs/api.json")
        }
    }

    install(createRouteScopedPlugin("CheckClientVersion", { })
    {
        val regex = listOf(
            "api-docs.*",
            "terminal.*",
            "ai/chat/[0-9a-zA-Z]+/file/[^/]+/data",
        ).map(::Regex)
        onCall()
        {
            val path = it.request.path().removePrefix(rootPath).removePrefix("/")
            if (path.isEmpty() || regex.any { r -> r.matches(path) })
                return@onCall
            val clientVersion = it.request.header("X-Client-Version")?.toLongOrNull()
            if (clientVersion == null || clientVersion < systemConfig.minVersionId)
                finishCall(HttpStatus.VersionTooOld)
        }
    })

    authenticate("auth", optional = true)
    {
        oauth()

        admin()
        ai()
        clazz()
        exam()
        home()
        knowledgePoint()
        practice()
        preparationGroup()
        quiz()
        section()
        subject()
        terminal()
        user()
    }
}