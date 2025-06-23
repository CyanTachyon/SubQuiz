package moe.tachyon.quiz.dataClass

import kotlinx.serialization.Serializable

@Suppress("unused")
@Serializable
enum class Permission
{
    /**
     * 被封禁的用户
     */
    BANNED,

    /**
     * 普通用户
     */
    NORMAL,

    /**
     * 管理员用户
     */
    ADMIN,

    /**
     * 根用户(超级管理员)
     */
    ROOT,

    ;

    fun isAdmin() = this == ADMIN || this == ROOT
}