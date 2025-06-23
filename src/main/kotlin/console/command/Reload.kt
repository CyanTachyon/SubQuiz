package moe.tachyon.quiz.console.command

import org.jline.reader.Candidate
import moe.tachyon.quiz.config.ConfigLoader

/**
 * Reload configs.
 */
object Reload: Command
{
    override val description = "Reload configs."

    override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
    {
        if (args.isEmpty()) ConfigLoader.reloadAll()
        else if (args.size == 1) ConfigLoader.reload(args[0])
        else return false
        sender.out("Reloaded.")
        return true
    }

    override suspend fun tabComplete(args: List<String>): List<Candidate> = ConfigLoader.configs().map(::Candidate)
}