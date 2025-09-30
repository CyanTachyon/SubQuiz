package moe.tachyon.quiz.utils

object UserConfigKeys
{
    // 自定义模型配置的存储Key
    const val CUSTOM_MODEL_CONFIG_KEY = "ai.chat.custom_model"
    // 禁止使用系统的Chat中提供的模型，但允许使用自定义模型
    const val FORBID_SYSTEM_MODEL_KEY = "ai.chat.forbid_system_model"
    // 禁止使用Chat功能
    const val FORBID_CHAT_KEY = "ai.chat.forbid_chat"

    // 是否是临时用户
    const val CUSTOM_USER_KEY = "custom_user"
}