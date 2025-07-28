package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.utils.ai.TokenUsage

@Serializable
sealed interface NamedUser
{
    val id: UserId
    val username: String
}

@Serializable
sealed interface PermissionUser
{
    val id: UserId
    val permission: Permission
}

@Serializable
sealed interface SsoUser: NamedUser
{
    override val id: UserId
    override val username: String
    val registrationTime: Long
    val email: List<String>
}

@Serializable
data class SsoUserFull(
    override val id: UserId,
    override val username: String,
    override val registrationTime: Long,
    val phone: String,
    override val email: List<String>,
    val seiue: List<Seiue>,
): SsoUser, NamedUser
{
    @Serializable
    data class Seiue(
        val studentId: String,
        val realName: String,
        val archived: Boolean,
    )
}

@Serializable
@Suppress("unused") // 该类作为SsoUser的默认实现, 在反序列化时使用, 故不应被标记为unused
data class SsoUserInfo(
    override val id: UserId,
    override val username: String,
    override val registrationTime: Long,
    override val email: List<String>,
): SsoUser, NamedUser

/**
 * 用户数据库数据类
 * @property id 用户ID
 * @property permission 用户管理权限
 */
@Serializable
data class DatabaseUser(
    override val id: UserId,
    override val permission: Permission,
    val tokenUsage: TokenUsage,
): PermissionUser
{
    companion object
    {
        val example = DatabaseUser(
            UserId(1),
            permission = Permission.NORMAL,
            tokenUsage = TokenUsage(),
        )
    }
}
fun PermissionUser?.hasGlobalAdmin() = this != null && (this.permission >= Permission.ADMIN)

@Serializable
data class UserFull(
    override val id: UserId,
    override val username: String,
    val registrationTime: Long,
    val phone: String,
    val email: List<String>,
    val seiue: List<SsoUserFull.Seiue>,
    override val permission: Permission,
    val tokenUsage: TokenUsage,
): NamedUser, PermissionUser
{
    fun toBasicUserInfo() = BasicUserInfo(id, username, registrationTime, email)
    fun toSsoUser() = SsoUserFull(id, username, registrationTime, phone, email, seiue)
    fun toDatabaseUser() = DatabaseUser(id, permission, tokenUsage)
    companion object
    {
        fun from(ssoUser: SsoUserFull, dbUser: DatabaseUser) = UserFull(
            ssoUser.id,
            ssoUser.username,
            ssoUser.registrationTime,
            ssoUser.phone,
            ssoUser.email,
            ssoUser.seiue,
            dbUser.permission,
            dbUser.tokenUsage,
        )
        val example = UserFull(
            UserId(1),
            "username",
            System.currentTimeMillis(),
            "phone",
            listOf("email"),
            listOf(SsoUserFull.Seiue("studentId", "realName", false)),
            permission = Permission.NORMAL,
            tokenUsage = TokenUsage(),
        )
    }
}

@Serializable
data class BasicUserInfo(
    override val id: UserId,
    override val username: String,
    val registrationTime: Long,
    val email: List<String>,
): NamedUser
{
    companion object
    {
        val example = BasicUserInfo(
            UserId(1),
            "username",
            System.currentTimeMillis(),
            listOf("email"),
        )
    }
}