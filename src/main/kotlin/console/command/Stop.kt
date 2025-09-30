package moe.tachyon.quiz.console.command

import moe.tachyon.quiz.utils.Power
import moe.tachyon.quiz.utils.ai.chatUtils.AiChatsUtils

/**
 * 杀死服务器
 */
object Stop: Command
{
    override val description = "Stop the server."
    override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
    {
        if (args.firstOrNull() != "confirm")
        {
            val chats = AiChatsUtils.getChats()
            if (chats.isNotEmpty())
            {
                sender.err("There are ${chats.size} active AI chat sessions. If you really want to stop the server, please run the command again with the argument \"confirm\".")
                chats.forEach { chat ->
                    sender.err(" - $chat")
                }
                return true
            }
        }
        Power.shutdown(0, "stop command executed.")
    }
}