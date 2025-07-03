package moe.tachyon.quiz.console.command

import me.nullaqua.api.reflect.FieldAccessor
import moe.tachyon.quiz.console.AnsiStyle.Companion.RESET
import moe.tachyon.quiz.console.AnsiStyle.Companion.ansi
import moe.tachyon.quiz.console.SimpleAnsiColor
import moe.tachyon.quiz.logger.SubQuizLogger
import org.jline.reader.Candidate
import org.jline.reader.CompletingParsedLine
import org.jline.reader.Parser
import org.jline.reader.impl.DefaultParser

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
    suspend fun handleCommandInvoke(sender: CommandSender, line: ParsedLine): Boolean
    suspend fun handleTabComplete(sender: CommandSender, line: ParsedLine): List<Candidate>
}

suspend fun CommandHandler.handleCommandInvoke(sender: CommandSender, line: String) =
    handleCommandInvoke(sender, LineParser.parse(line, line.length, Parser.ParseContext.ACCEPT_LINE) as ParsedLine)
suspend fun CommandHandler.handleTabComplete(sender: CommandSender, line: String) =
    handleTabComplete(sender, LineParser.parse(line, line.length, Parser.ParseContext.COMPLETE) as ParsedLine)

abstract class CommandSender(val name: String)
{
    abstract suspend fun out(line: String)
    abstract suspend fun err(line: String)
    abstract suspend fun clear()

    var handler: CommandHandler = CommandSet

    suspend fun invokeCommand(line: String) = invokeCommand(LineParser.parse(line, line.length, Parser.ParseContext.ACCEPT_LINE) as ParsedLine)
    suspend fun invokeCommand(line: ParsedLine) = logger.severe("An error occurred while processing the command: $line")
    {
        handler.handleCommandInvoke(this, line)
    }.getOrElse { true }

    suspend fun invokeTabComplete(line: String) = invokeTabComplete(LineParser.parse(line, line.length, Parser.ParseContext.COMPLETE) as ParsedLine)
    suspend fun invokeTabComplete(line: ParsedLine) = logger.severe("An error occurred while processing the command tab: $line")
    {
        handler.handleTabComplete(this, line)
    }.getOrElse { emptyList() }

    suspend fun invokeTabCompleteToStrings(line: String) =
        invokeTabCompleteToStrings(LineParser.parse(line, line.length, Parser.ParseContext.COMPLETE) as ParsedLine)
    suspend fun invokeTabCompleteToStrings(line: ParsedLine): List<String>
    {
        val words = line.words()
        val lastWord =
            if (words.size > line.wordIndex()) words[line.wordIndex()]
            else ""
        return invokeTabComplete(line)
            .groupBy { it.key() ?: it.value() }
            .mapNotNull { (_, value) -> value.firstOrNull()?.value() }
            .filter { it.startsWith(lastWord) }
    }

    fun parseLine(line: String, err: Boolean): String
    {
        val color = if (err) SimpleAnsiColor.RED.bright() else SimpleAnsiColor.BLUE.bright()
        val type = if (err) "[ERROR]" else "[INFO]"
        return SimpleAnsiColor.PURPLE.bright().ansi().toString() + "[COMMAND]" + color.ansi() + type + RESET + " " + line + RESET
    }
}

object LineParser: DefaultParser()
{
    private val openingQuoteGetter by lazy()
    {
        val getter = FieldAccessor.getField(ArgumentList::class.java, "openingQuote")
        if (getter != null) return@lazy {
            it: ArgumentList -> getter.get(it)
        }
        runCatching()
        {
            val field = ArgumentList::class.java.getDeclaredField("openingQuote")
            field.isAccessible = true
            return@lazy { it: ArgumentList ->
                field.get(it)
            }
        }
        return@lazy { _: ArgumentList -> null }
    }
    override fun parse(
        line: String,
        cursor: Int,
        context: Parser.ParseContext?
    ): org.jline.reader.ParsedLine?
    {
        val l = super.parse(line, cursor, context) as? ArgumentList
        if (l == null) return null
        return ParsedLineImpl(
            rawLine = line,
            line = l.line(),
            words = l.words(),
            wordIndex = l.wordIndex(),
            wordCursor = l.wordCursor(),
            cursor = l.cursor(),
            openingQuote = openingQuoteGetter(l) as? String?,
            rawWordCursor = l.rawWordCursor(),
            rawWordLength = l.rawWordLength()
        )
    }

    private class ParsedLineImpl(
        override val rawLine: String,
        line: String?,
        words: List<String?>?,
        wordIndex: Int,
        wordCursor: Int,
        cursor: Int,
        openingQuote: String?,
        rawWordCursor: Int,
        rawWordLength: Int
    ): ParsedLine, ArgumentList(
        line,
        words,
        wordIndex,
        wordCursor,
        cursor,
        openingQuote,
        rawWordCursor,
        rawWordLength
    )
    {
        override fun toString() = rawLine
    }
}

interface ParsedLine: org.jline.reader.ParsedLine, CompletingParsedLine
{
    val rawLine: String
}