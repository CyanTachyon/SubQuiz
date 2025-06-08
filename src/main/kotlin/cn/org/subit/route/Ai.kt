@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.route.ai

import cn.org.subit.dataClass.Section
import cn.org.subit.plugin.contentNegotiation.QuestionAnswerSerializer
import cn.org.subit.plugin.contentNegotiation.contentNegotiationJson
import cn.org.subit.route.utils.finishCall
import cn.org.subit.route.utils.getLoginUser
import cn.org.subit.utils.HttpStatus
import cn.org.subit.utils.ai.AiRequest
import cn.org.subit.utils.ai.StreamAiResponse
import cn.org.subit.utils.ai.ask.AskService
import cn.org.subit.utils.ai.ask.BdfzHelperAskService
import cn.org.subit.utils.ai.ask.QuizAskService
import cn.org.subit.utils.statuses
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.milliseconds

fun Route.ai() = route("/ai", {
    tags("ai")
})
{
    route("/ask", HttpMethod.Post,{
        description = "AI追问接口"
        request {
            body<AskRequest> {
                required = true
                description = "AI追问请求体"
            }
        }
        response {
            statuses<StreamAiResponse.Choice.Message>(HttpStatus.OK, example = StreamAiResponse.Choice.Message("content", "reasoning"))
            statuses(HttpStatus.Unauthorized, HttpStatus.BadRequest)
        }
    })
    {
        sse(serialize = { typeInfo, obj ->
            val ser = contentNegotiationJson.serializersModule.serializer(typeInfo.kotlinType!!)
            contentNegotiationJson.encodeToString(ser, obj)
        })
        {
            heartbeat {
                period = 50.milliseconds
            }
            call.getLoginUser() ?: finishCall(HttpStatus.Unauthorized)
            val body = call.receive<AskRequest>()
            body.model.service.ask(
                body.section,
                body.history.map { AiRequest.Message(it.role, it.content) },
                body.content
            )
            { message ->
                send(event = "message", data = message)
            }
        }
    }
}

@Serializable
data class AskRequest(
    @Serializable(SectionSerializer::class)
    val section: Section<@Contextual Any, @Contextual Any, String>,
    val history: List<AiRequest.Message>,
    val content: String,
    val model: Model
)
{
    @Serializable
    enum class Model(val service: AskService)
    {
        BDFZ_HELPER(BdfzHelperAskService),
        QUIZ_AI(QuizAskService);
    }

    class SectionSerializer: KSerializer<Section<Any, Any, String>> by ser

    companion object
    {
        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        val ser = Section.serializer(
            QuestionAnswerSerializer,
            QuestionAnswerSerializer,
            String.serializer()
        )
    }
}