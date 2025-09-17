@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.route.parctice

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.routing.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonElement
import moe.tachyon.quiz.dataClass.*
import moe.tachyon.quiz.dataClass.ClassId.Companion.toClassIdOrNull
import moe.tachyon.quiz.dataClass.PracticeId.Companion.toPracticeIdOrNull
import moe.tachyon.quiz.database.ClassMembers
import moe.tachyon.quiz.database.Practices
import moe.tachyon.quiz.database.Quizzes
import moe.tachyon.quiz.database.Sections
import moe.tachyon.quiz.plugin.contentNegotiation.QuestionAnswerSerializer
import moe.tachyon.quiz.route.utils.*
import moe.tachyon.quiz.utils.HttpStatus
import moe.tachyon.quiz.utils.statuses
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

fun Route.practice() = route("/practice", {
    tags("practice")
})
{
    post({
        response()
        {
            statuses<PracticeId>(HttpStatus.OK)
            statuses(HttpStatus.BadRequest, HttpStatus.Forbidden)
        }
    }, Context::createPractice)

    get("/list", {
        request()
        {
            queryParameter<ClassId>("class")
            {
                required = true
                description = "班级id"
            }
            paged()
        }
        response()
        {
            statuses<List<Practice>>(HttpStatus.OK, example = listOf(Practice.example))
        }
    }, Context::getPractices)

    get("/unfinished", {
        response()
        {
            statuses<List<Practice>>(HttpStatus.OK, example = listOf(Practice.example))
        }
    }, Context::getUnfinishedPractices)

    put({
        response()
        {
            statuses(HttpStatus.OK, HttpStatus.NotFound)
        }
    }, Context::updatePractice)

    route("/{id}", {
        request()
        {
            pathParameter<Int>("id")
            {
                required = true
                description = "练习id"
            }
        }
    })
    {
        get({
            response()
            {
                statuses<GetPracticeResponse>(HttpStatus.OK)
                statuses(HttpStatus.NotFound)
            }
        }, Context::getPractice)

        post("/start", {
            response()
            {
                statuses<QuizId>(HttpStatus.OK)
                statuses(HttpStatus.NotFound)
            }
        }, Context::startPractice)

        delete({
            response()
            {
                statuses(HttpStatus.OK, HttpStatus.NotFound)
            }
        }, Context::removePractice)
    }
}

@Serializable
data class GetPracticeResponse(
    val practice: Practice,
    @Serializable(with = QuizSerializer::class)
    val quizzes: List<Quiz<@Contextual Any?, @Contextual Any?, JsonElement?>>,
    val completed: Boolean,
    val users: List<UserProgress>,
)
{
    @OptIn(InternalSerializationApi::class)
    private class QuizSerializer : KSerializer<List<Quiz<Any?, Any?, JsonElement?>>> by quizSerializer

    companion object: KoinComponent
    {
        @OptIn(InternalSerializationApi::class)
        private val quizSerializer = ListSerializer(
            Quiz.serializer(
                QuestionAnswerSerializer.nullable,
                QuestionAnswerSerializer.nullable,
                JsonElement.serializer().nullable,
            )
        )

        suspend operator fun invoke(user: UserFull, id: PracticeId): GetPracticeResponse?
        {
            val practices: Practices by inject()
            val quizzes: Quizzes by inject()
            val practice = practices.getPractice(id) ?: return null
            val quiz = quizzes.getQuiz(user.id, id)
            val users = practices.getUsersInPractice(id)?.sortedBy { - (it.second ?: -1.0) } ?: return null
            if (users.all { it.first.user != user.id } && !user.hasGlobalAdmin()) return null
            val completed = users.firstOrNull { it.first.user == user.id }?.second.let { it != null && it >= practice.accuracy }
            return GetPracticeResponse(
                practice = practice,
                quizzes = quiz.map { if (!it.finished) it.hideAnswer() else it },
                completed = completed,
                users = users.map()
                {  (member, accuracy) ->
                    UserProgress(
                        member,
                        completed = accuracy != null && accuracy >= practice.accuracy,
                        accuracy = accuracy,
                    )
                }
            )
        }
    }

    @Serializable
    data class UserProgress(
        val member: ClassMember,
        val completed: Boolean,
        val accuracy: Double?,
    )
}

private suspend fun Context.createPractice(practice: Practice): Nothing
{
    val checked = practice.check()
    if (checked != null) finishCall(HttpStatus.BadRequest.subStatus(checked, 1))
    if (!loginUser.isAdminIn(practice.clazz)) finishCall(HttpStatus.Forbidden)
    val id = get<Practices>().createPractice(practice)
    finishCall(HttpStatus.OK, id)
}

private suspend fun Context.getPractices(): Nothing
{
    val clazzId = call.request.queryParameters["class"]?.toClassIdOrNull()
                  ?: finishCall(HttpStatus.BadRequest.subStatus("class id is required", 1))
    val (begin, count) = call.getPage()
    if (!loginUser.isInClass(clazzId)) finishCall(HttpStatus.Forbidden)
    val practices = get<Practices>().getPractices(clazzId, !loginUser.isAdminIn(clazzId), begin, count)
    finishCall(HttpStatus.OK, practices)
}

private suspend fun Context.getUnfinishedPractices(): Nothing
{
    if (loginUser.hasGlobalAdmin()) finishCall(HttpStatus.OK, emptyList<Practice>())
    val practices = get<Practices>().getUnfinishedPractices(loginUser.id)
    finishCall(HttpStatus.OK, practices)
}

private suspend fun Context.getPractice(): Nothing
{
    val practiceId = call.pathParameters["id"]?.toPracticeIdOrNull()
                     ?: finishCall(HttpStatus.BadRequest.subStatus("practice id is required", 1))
    val practice = GetPracticeResponse(loginUser, practiceId) ?: finishCall(HttpStatus.NotFound)
    if (!practice.practice.available && !loginUser.isAdminIn(practice.practice.clazz))
        finishCall(HttpStatus.NotFound)
    finishCall(HttpStatus.OK, practice)
}

private suspend fun Context.updatePractice(practice: Practice): Nothing
{
    val checked = practice.check()
    if (checked != null) finishCall(HttpStatus.BadRequest.subStatus(checked, 1))
    if (!loginUser.isAdminIn(practice.clazz)) finishCall(HttpStatus.Forbidden)
    if (get<Practices>().updatePractice(practice)) finishCall(HttpStatus.OK)
    else finishCall(HttpStatus.NotFound)
}

private suspend fun Context.startPractice(): Nothing
{
    val practiceId = call.pathParameters["id"]?.toPracticeIdOrNull()
                     ?: finishCall(HttpStatus.BadRequest.subStatus("practice id is required", 1))
    val practices: Practices = get()
    val practice = practices.getPractice(practiceId) ?: finishCall(HttpStatus.NotFound)
    if (!practice.available) finishCall(HttpStatus.BadRequest.subStatus("练习不可用", 2))
    if (loginUser.isAdminIn(practice.clazz))
        finishCall(HttpStatus.Forbidden.copy(message = "作为老师无法参与练习"))
    val members = get<ClassMembers>().getClassMembers(practice.clazz)
    if (members.all { it.user != loginUser.id }) finishCall(HttpStatus.NotFound)
    val quizzes: Quizzes = get()
    val sections = get<Sections>().recommendSections(loginUser.id, null, practice.knowledgePoints, practice.sectionCount)
    if (sections.count < practice.sectionCount ) finishCall(HttpStatus.NotEnoughQuestions.subStatus("请联系你的老师添加更多题目", 1))
    val quiz = quizzes.addQuiz(loginUser.id, sections.list, null, practiceId).hideAnswer()
    finishCall(HttpStatus.OK, quiz.id)
}

private suspend fun Context.removePractice(): Nothing
{
    val practiceId = call.pathParameters["id"]?.toPracticeIdOrNull()
                     ?: finishCall(HttpStatus.BadRequest.subStatus("practice id is required", 1))
    val practice = get<Practices>().getPractice(practiceId) ?: finishCall(HttpStatus.NotFound)
    if (!loginUser.isAdminIn(practice.clazz)) finishCall(HttpStatus.Forbidden)
    if (get<Practices>().removePractice(practiceId)) finishCall(HttpStatus.OK)
    else finishCall(HttpStatus.NotFound)
}