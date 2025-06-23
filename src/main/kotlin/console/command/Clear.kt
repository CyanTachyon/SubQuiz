package moe.tachyon.quiz.console.command

/**
 * 清屏命令
 */
object Clear: Command
{
    override val description = "Clear screen"
    override val log = false
    override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
    {
        sender.clear()
        return true
    }
}