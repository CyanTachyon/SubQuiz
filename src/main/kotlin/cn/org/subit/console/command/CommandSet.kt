package cn.org.subit.console.command

import cn.org.subit.console.AnsiStyle.Companion.RESET
import cn.org.subit.console.AnsiStyle.Companion.ansi
import cn.org.subit.console.Console
import cn.org.subit.console.SimpleAnsiColor
import cn.org.subit.logger.SubQuizLogger
import cn.org.subit.utils.Power.shutdown
import io.ktor.server.application.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jline.reader.*
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
    SSO,
    TestDatabase,
)
{
    private val logger = SubQuizLogger.getLogger()

    private var success = true
    private fun parsePrompt(prompt: String): String =
        "${if (success) SimpleAnsiColor.CYAN.bright() else SimpleAnsiColor.RED.bright()}$prompt${RESET}"
    private val prompt: String get() = parsePrompt("SubQuiz > ")
    private val rightPrompt: String get() = parsePrompt("<| POWERED BY TACHYON |>")

    @OptIn(DelicateCoroutinesApi::class)
    fun Application.startCommandThread() = GlobalScope.launch()
    {
        if (Console.lineReader == null) return@launch
        var line: String?
        while (true)
        {
            try
            {
                line = Console.lineReader.readLine(prompt, rightPrompt, null as Char?, null)
            }
            catch (e: UserInterruptException)
            {
                Console.onUserInterrupt(ConsoleCommandSender)
            }
            catch (_: EndOfFileException)
            {
                logger.warning("Console is closed")
                shutdown(0, "Console is closed")
            }
            if (line == null) continue
            invokeCommand(ConsoleCommandSender, line)
        }
    }.start()

    suspend fun invokeCommand(sender: CommandSender, line: String) = logger.severe("An error occurred while processing the command: $line")
    {
        val words = DefaultParser().parse(line, 0, Parser.ParseContext.ACCEPT_LINE).words()
        if (words.isEmpty() || (words.size == 1 && words.first().isEmpty())) return
        val command = getCommand(words[0])
        if (command != null && command.log) logger.info("${sender.name} is executing command: $line")
        success = false
        if (command == null)
            sender.err("Unknown command: ${words[0]}, use \"help\" to get help")
        else if (!command.execute(sender, words.subList(1, words.size)))
            sender.err("Command is illegal, use \"help ${words[0]}\" to get help")
        else success = true
    }

    suspend fun invokeTabComplete(line: String): List<String>
    {
        val parsedLine = DefaultParser().parse(line, line.length, Parser.ParseContext.ACCEPT_LINE)
        val words = parsedLine.words()
        val lastWord =
            if (words.size > parsedLine.wordIndex()) words[parsedLine.wordIndex()]
            else ""
        return tabComplete(words).map { it.value() }.filter { it.startsWith(lastWord) }
    }

    /**
     * Command completer.
     */
    object CommandCompleter: Completer
    {
        override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>?)
        {
            logger.severe("An error occurred while tab completing")
            {
                val candidates1 = runBlocking { tabComplete(line.words().subList(0, line.wordIndex() + 1)) }
                candidates?.addAll(candidates1)
            }
        }
    }

    interface CommandSender
    {
        val name: String
        suspend fun out(line: String)
        suspend fun err(line: String)
        suspend fun clear()

        fun parseLine(line: String, err: Boolean): String
        {
            val color = if (err) SimpleAnsiColor.RED.bright() else SimpleAnsiColor.BLUE.bright()
            val type = if (err) "[ERROR]" else "[INFO]"
            return SimpleAnsiColor.PURPLE.bright().ansi().toString() + "[COMMAND]" + color.ansi() + type + RESET + " " + line + RESET
        }
    }

    object ConsoleCommandSender: CommandSender
    {
        override val name: String = "Console"
        override suspend fun out(line: String) = Console.println(parseLine(line, false))
        override suspend fun err(line: String) = Console.println(parseLine(line, true))
        override suspend fun clear() = Console.clear()
    }
}