package moe.tachyon.quiz.console

import io.ktor.server.application.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.tachyon.quiz.console.AnsiStyle.Companion.RESET
import moe.tachyon.quiz.console.Console.historyFile
import moe.tachyon.quiz.console.command.CommandSender
import moe.tachyon.quiz.console.command.LineParser
import moe.tachyon.quiz.console.command.ParsedLine
import moe.tachyon.quiz.dataDir
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.logger.SubQuizLogger.nativeOut
import moe.tachyon.quiz.utils.Power.shutdown
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.widget.AutopairWidgets
import org.jline.widget.AutosuggestionWidgets
import sun.misc.Signal
import java.io.File

/**
 * 终端相关
 */
object Console
{
    private val logger = SubQuizLogger.getLogger<Console>()
    /**
     * 终端对象
     */
    private val terminal: Terminal?

    /**
     * 颜色显示模式
     */
    var ansiColorMode: ColorDisplayMode = ColorDisplayMode.RGB

    /**
     * 效果显示模式
     */
    var ansiEffectMode: EffectDisplayMode = EffectDisplayMode.ON

    /**
     * 命令行读取器,命令历史保存在[historyFile]中
     */
    val lineReader: LineReaderImpl?

    init
    {
        var terminal: Terminal? = null
        var lineReader: LineReaderImpl? = null
        try
        {
            terminal = TerminalBuilder.builder().jansi(true).build()
            if (terminal.type == "dumb")
            {
                terminal.close()
                terminal = null
                throw IllegalStateException("Unsupported terminal type: dumb")
            }

            Signal.handle(Signal("INT")) { onUserInterrupt(ConsoleCommandSender) }
            Signal.handle(Signal("TSTP")) { onUserInterrupt(ConsoleCommandSender) }
            Signal.handle(Signal("WINCH")) { Console.lineReader?.redrawLine() }
            terminal.handle(Terminal.Signal.INT) { onUserInterrupt(ConsoleCommandSender) }
            terminal.handle(Terminal.Signal.TSTP) { onUserInterrupt(ConsoleCommandSender) }
            terminal.handle(Terminal.Signal.WINCH) { Console.lineReader?.redrawLine() }

            lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(LineParser)
                .completer()
                { _, line, candidates ->
                    line as ParsedLine
                    candidates?.addAll(runBlocking { ConsoleCommandSender.invokeTabComplete(line) })
                }
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build() as LineReaderImpl
            val autopairWidgets = AutopairWidgets(lineReader, true)
            autopairWidgets.enable()
            // 根据历史记录建议
            val autosuggestionWidgets = AutosuggestionWidgets(lineReader)
            autosuggestionWidgets.enable()
        }
        catch (_: Throwable)
        {
            terminal?.close()
            println("Failed to initialize terminal, will use system console instead.")
        }
        this.terminal = terminal
        this.lineReader = lineReader
    }

    fun onUserInterrupt(sender: CommandSender) = runBlocking()
    {
        sender.err("You might have pressed Ctrl+C or performed another operation to stop the server.")
        sender.err(
            "This method is feasible but not recommended, " +
            "it should only be used when a command-line system error prevents the program from closing."
        )
        sender.err("If you want to stop the server, please use the \"stop\" command.")
    }

    /**
     * 命令历史文件
     */
    private val historyFile: File
        get() = File(dataDir, "command_history.txt")

    private var success = true
    private fun parsePrompt(prompt: String): String =
        "${if (success) SimpleAnsiColor.CYAN.bright() else SimpleAnsiColor.RED.bright()}$prompt${RESET}"
    private val prompt: String get() = parsePrompt("SubQuiz > ")
    private val rightPrompt: String get() = parsePrompt("<| POWERED BY TACHYON |>")

    object ConsoleCommandSender: CommandSender("Console")
    {
        override suspend fun out(line: String) = println(parseLine(line, false))
        override suspend fun err(line: String) = println(parseLine(line, true))
        override suspend fun clear() = Console.clear()
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun Application.startConsoleCommandHandler() = GlobalScope.launch()
    {
        if (lineReader == null) return@launch
        var line: String
        while (true)
        {
            try
            {
                lineReader.readLine(prompt, rightPrompt, null as Char?, null)
                line = (lineReader.parsedLine as ParsedLine).rawLine
            }
            catch (_: UserInterruptException)
            {
                onUserInterrupt(ConsoleCommandSender)
                continue
            }
            catch (_: EndOfFileException)
            {
                logger.warning("Console is closed")
                shutdown(0, "Console is closed")
            }
            success = ConsoleCommandSender.invokeCommand(line)
        }
    }.start()

    /**
     * 在终端上打印一行, 会自动换行并下移命令提升符和已经输入的命令
     */
    fun println(o: Any)
    {
        if (lineReader != null && lineReader.isReading)
            return lineReader.printAbove("\r$o")
        terminal?.writer()?.println(o) ?: nativeOut.println(o)
    }

    /**
     * 清空终端
     */
    fun clear()
    {
        nativeOut.print("\u001bc")
        if (lineReader != null && lineReader.isReading)
            lineReader.redrawLine()
    }
}