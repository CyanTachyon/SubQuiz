package moe.tachyon.quiz.console.command

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jline.reader.Candidate

object Run: Command
{
    private class RunHandler(val process: Process): CommandHandler
    {
        private val o = process.outputWriter()
        override suspend fun handleTabComplete(sender: CommandSender, line: ParsedLine): List<Candidate>
        {
            val rawLine = line.rawLine
            if (' ' in rawLine)
            {
                if (rawLine.startsWith("/cmd "))
                {
                    return CommandSet.handleTabComplete(sender, rawLine.substring(5))
                }
                return emptyList()
            }
            return listOf(
                Candidate("/i"),
                Candidate("/cmd"),
                Candidate("/exit"),
                Candidate("/close"),
            )
        }
        override suspend fun handleCommandInvoke(sender: CommandSender, line: ParsedLine): Boolean
        {
            val rawLine = line.rawLine
            if (rawLine.startsWith("/"))
            {
                if (rawLine.startsWith("/i "))
                {
                    o.write(rawLine.substring(3) + "\n")
                    o.flush()
                }
                else if (rawLine.startsWith("/cmd "))
                {
                    return CommandSet.handleCommandInvoke(sender, rawLine.substring(5))
                }
                else if (rawLine == "/i")
                {
                    sender.err("Please use /i <input> to send input to the process.")
                }
                else if (rawLine == "/exit")
                {
                    process.destroyForcibly().destroyForcibly().destroyForcibly()
                }
                else if (rawLine == "/close")
                {
                    o.close()
                    sender.out("Process input stream closed. You can no longer send input to the process.")
                }
                else
                {
                    sender.err("Invalid command for run handler: $line")
                    sender.err("commands: /i <input>, /cmd <cmd>, /exit, /close. or just input your command directly(equivalent to /i <input>)")
                }
            }
            else
            {
                o.write(rawLine + "\n")
                o.flush()
            }
            return true
        }
    }

    override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
    {
        if (sender.handler is RunHandler)
        {
            sender.err("You are already running a command. Please exit the current command first.")
            return false
        }
        val coroutineScope = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler()
        { _, throwable ->
            runCatching()
            {
                runBlocking()
                {
                    sender.err("An error occurred while executing the command: ${args.joinToString(" ")}")
                    sender.err("Error: ${throwable.message}")
                }
            }
        })
        coroutineScope.launch()
        {
            try
            {
                val process = Runtime.getRuntime().exec(args.toTypedArray())
                sender.out("您的输入将被劫持到命令行: ${args.joinToString(" ")}")
                sender.handler = RunHandler(process)
                val job0 = launch()
                {
                    val reader = process.inputReader()
                    while (true)
                    {
                        val line = reader.readLine() ?: break
                        sender.out(line)
                    }
                }
                val job1 = launch()
                {
                    val errorReader = process.errorReader()
                    while (true)
                    {
                        val line = errorReader.readLine() ?: break
                        sender.err(line)
                    }
                }
                val exitCode = process.waitFor()
                job0.join()
                job1.join()
                if (exitCode == 0)
                    sender.out("Command executed successfully: ${args.joinToString(" ")}")
                else
                    sender.err("Command failed with exit code $exitCode: ${args.joinToString(" ")}")
            }
            catch (e: Exception)
            {
                sender.err("Failed to execute command: ${args.joinToString(" ")}")
                sender.err("Error: ${e.message}")
            }
            finally
            {
                if (sender.handler is RunHandler)
                {
                    sender.handler = CommandSet
                    sender.out("命令结束，您的输入将不再被劫持。")
                }
            }
        }
        return true
    }
}