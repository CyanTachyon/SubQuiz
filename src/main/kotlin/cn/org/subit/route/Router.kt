package cn.org.subit.route

import cn.org.subit.route.adimin.admin
import cn.org.subit.route.ai.ai
import cn.org.subit.route.exam.exam
import cn.org.subit.route.knowledgePoint.knowledgePoint
import cn.org.subit.route.oauth.oauth
import cn.org.subit.route.preparationGroup.preparationGroup
import cn.org.subit.route.quiz.quiz
import cn.org.subit.route.section.section
import cn.org.subit.route.subject.subject
import cn.org.subit.route.terminal.terminal
import cn.org.subit.route.user.user
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.router() = routing()
{
    val rootPath = this.application.rootPath

    get("/", { hidden = true })
    {
        call.respondRedirect("$rootPath/api-docs")
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

    oauth()

    authenticate("auth", optional = true)
    {
        admin()
        ai()
        exam()
        knowledgePoint()
        preparationGroup()
        quiz()
        section()
        subject()
        terminal()
        user()
    }
}