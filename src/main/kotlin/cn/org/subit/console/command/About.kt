package cn.org.subit.console.command

import cn.org.subit.version
import kotlinx.serialization.Serializable

/**
 * About command.
 * print some info about this server.
 */
object About: Command
{
    override val description = "Show about."
    override val aliases = listOf("version", "ver")
    val author = Author(
        "CyanTachyon",
        "cyan@tachyon.moe",
        "https://www.tachyon.moe/",
        "https://github.com/CyanTachyon",
    )

    override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
    {
        sender.out("SubQuiz")
        sender.out("Version: $version")
        sender.out("Author: ${author.name}")
        sender.out("Github: ${author.github}")
        sender.out("Website: ${author.website}")
        sender.out("Email: ${author.email}")
        return true
    }

    @Serializable
    data class Author(
        val name: String,
        val email: String,
        val website: String,
        val github: String,
    )
    {
        companion object
        {
            val example = Author("Author", "email@email.com", "https://example.com", "https://github.com")
        }
    }
}