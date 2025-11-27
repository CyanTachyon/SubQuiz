package moe.tachyon.quiz.console.command

import moe.tachyon.quiz.Loader
import moe.tachyon.quiz.console.AnsiEffect
import moe.tachyon.quiz.console.SimpleAnsiColor
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.version
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
        "https://github.com/CyanTachyon/SubQuiz",
    )

    override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
    {
        Loader.getResource(Loader.SUB_QUIZ_LOGO)
            ?.bufferedReader()
            ?.lines()
            ?.toList()
            ?.forEach { sender.out("${SimpleAnsiColor.BLUE}${AnsiEffect.BOLD}$it") }
            ?: SubQuizLogger.getLogger().severe("${Loader.SUB_QUIZ_LOGO} not found")

        Loader.getResource(Loader.CYAN_LOGO)
            ?.bufferedReader()
            ?.lines()
            ?.toList()
            ?.forEach { sender.out("${SimpleAnsiColor.CYAN}${AnsiEffect.BOLD}$it") }
            ?: SubQuizLogger.getLogger().severe("${Loader.CYAN_LOGO} not found")

        sender.out("|> Version: $version")
        sender.out("|> Author: ${author.name}")
        sender.out("|> Github: ${author.github}")
        sender.out("|> Website: ${author.website}")
        sender.out("|> Email: ${author.email}")
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