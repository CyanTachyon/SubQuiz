package moe.tachyon.quiz.console.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import moe.tachyon.quiz.utils.ai.chat.tools.AiLibrary
import kotlin.time.measureTime

object AiLibrary: TreeCommand(Update, Clear, Remove, Indexed, Search, SearchInOrder)
{
    object Update: Command
    {
        override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
        {
            CoroutineScope(Dispatchers.IO).launch()
            {
                sender.out("updating AI library...")
                AiLibrary.updateLibrary()
                sender.out("update done.")
            }
            return true
        }
    }

    object Clear: Command
    {
        override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
        {
            AiLibrary.clear()
            sender.out("AI library cleared.")
            return true
        }
    }

    object Indexed: Command
    {
        override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
        {
            val indexed = AiLibrary.getAllFiles()
            if (indexed.isEmpty())
            {
                sender.out("No indexed files.")
            }
            else
            {
                indexed.forEach { sender.out(it) }
            }
            return true
        }
    }

    object Remove: Command
    {
        override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
        {
            if (args.size != 1)
            {
                sender.err("Usage: remove <filePath>")
                return false
            }
            val filePath = args[0]
            AiLibrary.remove(filePath)
            sender.out("File $filePath removed from AI library.")
            return true
        }
    }

    object Search: Command
    {
        override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
        {
            if (args.size != 1)
            {
                sender.err("Usage: search <query>")
                return false
            }
            val query = args[0]
            val results = AiLibrary.search("", query, 10)
            results.forEach { sender.out("${it.second}: ${it.first}") }
            return true
        }
    }

    object SearchInOrder: Command
    {
        override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
        {
            if (args.size != 1)
            {
                sender.err("Usage: searchInOrder <query>")
                return false
            }
            val query = args[0]
            val results: List<String>
            val time = measureTime()
            {
                results = AiLibrary.searchInOrder("", query, 3)
            }
            sender.out(showJson.encodeToString(results.map { it.take(50) }))
            sender.out("Search completed in $time")
            return true
        }
    }
}