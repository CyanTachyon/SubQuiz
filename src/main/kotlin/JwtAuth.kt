package moe.tachyon.quiz

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.Application
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import moe.tachyon.quiz.console.SimpleAnsiColor
import moe.tachyon.quiz.dataClass.SsoUserFull
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.CustomUsers
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.SSO
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
object JwtAuth: KoinComponent
{
    private val logger = SubQuizLogger.getLogger<JwtAuth>()
    private val customUsers: CustomUsers by inject()

    val TOKEN_VALIDITY = 30.days

    private lateinit var SECRET_KEY: String

    private lateinit var algorithm: Algorithm

    fun Application.initJwtAuth()
    {
        val key = environment.config.propertyOrNull("jwt.secret")?.getString()
        if (key == null)
        {
            logger.info("${SimpleAnsiColor.CYAN}jwt.secret${SimpleAnsiColor.RED} not found in config file, use random secret key")
            SECRET_KEY = UUID.randomUUID().toString()
        }
        else
        {
            SECRET_KEY = key
        }
        algorithm = Algorithm.HMAC512(SECRET_KEY)
    }

    fun makeToken(user: UserId): String
    {
        if (SSO.isSsoUser(user)) error("Cannot make token for SSO user")
        return JWT.create()
            .withSubject("Authentication")
            .withClaim("type", "SubQuizUser")
            .withClaim("author", "CyanTachyon")
            .withClaim("id", user.value)
            .withIssuer("SubQuiz")
            .withIssuedAt(OffsetDateTime.now().toInstant())
            .withExpiresAt((OffsetDateTime.now().toInstant().toKotlinInstant() + TOKEN_VALIDITY).toJavaInstant())
            .sign(algorithm)
            .let { SSO.CUSTOM_USER_PREFIX + it }
    }

    suspend fun checkToken(token: String): UserId?
    {
        if (SSO.isSsoUser(token)) error("Cannot check token for SSO user")
        val verifier = JWT.require(algorithm).build()
        return try
        {
            val decoded = verifier.verify(token.removePrefix(SSO.CUSTOM_USER_PREFIX))
            if (decoded.getClaim("type").asString() != "SubQuizUser") error("Invalid token type")
            if (decoded.expiresAt.toInstant() < OffsetDateTime.now().toInstant()) error("Token expired")
            if (decoded.issuer != "SubQuiz") error("Invalid token issuer")
            if (decoded.getClaim("author").asString() != "CyanTachyon") error("Invalid token author")
            val user = UserId(decoded.getClaim("id").asInt())
            val db = customUsers.getUser(-user) ?: error("User not found")
            val lastPasswordChange = db.lastPasswordChange
            if (decoded.issuedAt.toInstant() < lastPasswordChange.toJavaInstant()) error("Token issued before last password change")
            user
        }
        catch (e: Throwable)
        {
            logger.fine("Invalid token", e)
            null
        }
    }

    suspend fun getSsoUser(token: String): SsoUserFull?
    {
        val user = checkToken(token) ?: return null
        return customUsers.getUser(-user)?.let()
        {
            SsoUserFull(
                id = user,
                username = it.name,
                registrationTime = System.currentTimeMillis(),
                phone = "",
                email = listOf("${-user}@local"),
                seiue = listOf(
                    SsoUserFull.Seiue(
                        studentId = "SubQuiz-${-user}",
                        realName = it.name,
                        archived = false,
                    )
                )
            )
        }
    }

    private val hasher = BCrypt.with(BCrypt.Version.VERSION_2B)
    private val verifier = BCrypt.verifyer(BCrypt.Version.VERSION_2B)

    /**
     * 在数据库中保存密码的加密
     */
    fun encryptPassword(password: String): String = hasher.hashToString(12, password.toCharArray())
    fun verifyPassword(password: String, hash: String): Boolean = verifier.verify(password.toCharArray(), hash).verified

    suspend fun checkPassword(user: UserId, password: String): Boolean
    {
        val db = customUsers.getUser(-user) ?: return false
        return verifyPassword(password, db.password)
    }
}