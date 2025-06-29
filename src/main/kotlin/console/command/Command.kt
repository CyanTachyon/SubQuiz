package moe.tachyon.quiz.console.command

import moe.tachyon.quiz.console.AnsiStyle.Companion.RESET
import moe.tachyon.quiz.console.AnsiStyle.Companion.ansi
import moe.tachyon.quiz.console.SimpleAnsiColor
import moe.tachyon.quiz.logger.SubQuizLogger
import org.jline.reader.Candidate

private val logger = SubQuizLogger.getLogger<Command>()

/**
 * Command interface.
 */
interface Command
{
    /**
     * Command name.
     * default: class name without package name in lowercase.
     */
    val name: String
        get() = TreeCommand.parseName(this.javaClass.simpleName.split(".").last().lowercase())

    /**
     * Command description.
     * default: "No description."
     */
    val description: String
        get() = "No description."

    /**
     * Command args.
     * default: no args.
     */
    val args: String
        get() = "no args"

    /**
     * Command aliases.
     * default: empty list.
     */
    val aliases: List<String>
        get() = emptyList()

    /**
     * Whether to log the command.
     * default: true
     */
    val log: Boolean
        get() = true

    /**
     * Execute the command.
     * @param args Command arguments.
     * @return Whether the command is executed successfully.
     */
    suspend fun execute(sender: CommandSender, args: List<String>): Boolean = false

    /**
     * Tab complete the command.
     * default: empty list.
     * @param args Command arguments.
     * @return Tab complete results.
     */
    suspend fun tabComplete(args: List<String>): List<Candidate> = emptyList()
}

interface CommandHandler
{
    suspend fun handleCommandInvoke(sender: CommandSender, line: String): Boolean
    suspend fun handleTabComplete(sender: CommandSender, line: String): List<Candidate>
}

abstract class CommandSender(val name: String)
{
    abstract suspend fun out(line: String)
    abstract suspend fun err(line: String)
    abstract suspend fun clear()

    var handler: CommandHandler = CommandSet

    suspend fun invokeCommand(line: String): Boolean
    {
        logger.severe("An error occurred while processing the command: $line")
        {
            return handler.handleCommandInvoke(this, line)
        }
        return true
    }

    suspend fun invokeTabComplete(line: String): List<Candidate> = runCatching()
    {
        handler.handleTabComplete(this, line)
    }.onFailure {
        logger.severe("An error occurred while processing the command tab: $line", it)
    }.getOrElse {
        emptyList()
    }

    fun parseLine(line: String, err: Boolean): String
    {
        val color = if (err) SimpleAnsiColor.RED.bright() else SimpleAnsiColor.BLUE.bright()
        val type = if (err) "[ERROR]" else "[INFO]"
        return SimpleAnsiColor.PURPLE.bright().ansi().toString() + "[COMMAND]" + color.ansi() + type + RESET + " " + line + RESET
    }
}