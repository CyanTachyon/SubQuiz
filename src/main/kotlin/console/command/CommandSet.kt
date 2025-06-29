package moe.tachyon.quiz.console.command

import moe.tachyon.quiz.logger.SubQuizLogger
import org.jline.reader.Candidate
import org.jline.reader.Parser
import org.jline.reader.impl.DefaultParser

/**
 * Command set.
 */
object CommandSet: TreeCommand(
    Reload,
    Config,
    Stop,
    Help,
    About,
    Clear,
    Logger,
    Color,
    Run,
    SSO,
    TestDatabase,
), CommandHandler
{
    private val logger = SubQuizLogger.getLogger()

    override suspend fun handleCommandInvoke(sender: CommandSender, line: String): Boolean
    {
        val words = DefaultParser().parse(line, 0, Parser.ParseContext.ACCEPT_LINE).words()
        if (words.isEmpty() || (words.size == 1 && words.first().isEmpty())) return true
        val command = getCommand(words[0])
        if (command != null && command.log) logger.info("${sender.name} is executing command: $line")
        if (command == null)
            sender.err("Unknown command: ${words[0]}, use \"help\" to get help")
        else if (!command.execute(sender, words.subList(1, words.size)))
            sender.err("Command is illegal, use \"help ${words[0]}\" to get help")
        else return true
        return false
    }

    override suspend fun handleTabComplete(sender: CommandSender, line: String): List<Candidate>
    {
        val parsedLine = DefaultParser().parse(line, line.length, Parser.ParseContext.ACCEPT_LINE)
        return tabComplete(parsedLine.words())
    }
}

suspend fun CommandSender.invokeTabCompleteToStrings(line: String): List<String>
{
    val parsedLine = DefaultParser().parse(line, line.length, Parser.ParseContext.ACCEPT_LINE)
    val words = parsedLine.words()
    val lastWord =
        if (words.size > parsedLine.wordIndex()) words[parsedLine.wordIndex()]
        else ""
    return invokeTabComplete(line).map { it.value() }.filter { it.startsWith(lastWord) }
}