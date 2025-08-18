package moe.tachyon.quiz.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.Serializable

@Serializable
data class SystemConfig(
    @YamlComment("SSO服务后端url, 请不要以/结尾, 例如: https://pkus.sso.subit.org.cn/api 而不是 https://pkus.sso.subit.org.cn/api/")
    val ssoServer: String,
    @YamlComment("SSO服务ID")
    val ssoServerId: Int,
    @YamlComment("SSO服务秘钥")
    val ssoSecret: String,
    val minVersionId: Long,
    @YamlComment("重复推送阈值, 若某个section在某次作答的正确率高于该阈值则不再重新推送. 例如60表示正确率高于60%不会再推送")
    val score: Double,
)

val systemConfig: SystemConfig by config(
    "system.yml",
    SystemConfig("https://pkus.sso.subit.org.cn/api", 1, "secret", 0, 60.0),
)