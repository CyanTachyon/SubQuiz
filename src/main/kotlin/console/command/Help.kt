package moe.tachyon.quiz.console.command

import org.jline.reader.Candidate

/**
 * Help command.
 */
object Help: Command
{
    override val description = "Show help."
    override val args = "[command]"
    override val log = false

    /**
     * 根据参数获取命令对象([TreeCommand]), 如果找不到就返回null
     */
    private fun getCommand(args: List<String>): Command?
    {
        var command: Command? = CommandSet
        for (str in args)
        {
            if (command==null) return null
            if (command is TreeCommand) command=command.getCommand(str)
            else return null
        }
        return command
    }

    override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
    {
        if (args.isEmpty()) // 直接使用help命令的话打印所有命令列表
        {
            sender.out("Commands:")
            for (command in CommandSet.allCommands()) // 每个命令的格式: 命令名|别名1|别名2 - 命令描述
            {
                val sb: StringBuilder = StringBuilder()
                sb.append("  ${TreeCommand.parseName(command.name)}")
                if (command.aliases.isNotEmpty())
                {
                    sb.append("|")
                    sb.append(command.aliases.joinToString("|"))
                }
                sb.append(" - ${command.description}")
                sender.out(sb.toString())
            }
        }
        else
        {
            val command = getCommand(args)
            if (command==null)
            {
                sender.err("Unknown command: ${args.dropLast(1).joinToString(" ")}")
                return true
            }
            val rawCmdName=args.dropLast(1).toMutableList() // 实际命令名
            rawCmdName.add(command.name)
            sender.out("Command: ${rawCmdName.joinToString(" ")}")
            sender.out("Description: ${command.description}")
            sender.out("Aliases: ${command.aliases.joinToString(", ")}")
            val cmdArgs=command.args.split("\n")
            if (cmdArgs.size>1)// 如果有多个参数，就分行打印
            {
                sender.out("Arguments:")
                cmdArgs.forEach { sender.out("  $it") }
            }
            else sender.out("Arguments: ${cmdArgs[0]}") // 否则就直接打印
        }
        return true
    }

    override suspend fun tabComplete(args: List<String>): List<Candidate>
    {
        return CommandSet.tabComplete(args)
    }
}