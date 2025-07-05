package moe.tachyon.quiz.console.command

import moe.tachyon.quiz.config.ConfigLoader
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import org.jline.reader.Candidate

object Config: TreeCommand(
    Get,
    Reload,
)
{
    override val description: String get() = "Get/Set config."

    /**
     * 设置配置，但设置配置后，无法保留原配置的格式、注释等信息。
     *
     * 因此，该命令暂时禁用，若未来有更好的实现方式以在不丢失原配置格式、
     * 注释等信息的情况下设置配置，则可以启用。
     */
    @Suppress("unused")
    object Set: Command
    {
        override val description: String get() = "Set config."

        override val args: String get() = "<config> <path>... <value>"

        override suspend fun tabComplete(args: List<String>): List<Candidate>
        {
            if (args.size <= 1) return ConfigLoader.configs().map(::Candidate)
            val config = ConfigLoader.getConfigLoader(args[0]) ?: return emptyList()
            var res: JsonElement = showJson.encodeToJsonElement(showJson.serializersModule.serializer(config.type), config.config)
            for (i in 1 until args.size - 1) res = when (res)
            {
                is JsonObject -> res[args[i]] ?: return emptyList()
                is JsonArray -> res.getOrNull(args[i].toIntOrNull() ?: return emptyList()) ?: return emptyList()
                is JsonPrimitive -> return emptyList()
            }
            return when (res)
            {
                is JsonObject -> res.keys.map(::Candidate)
                is JsonArray -> res.indices.map { it.toString() }.map(::Candidate)
                is JsonPrimitive -> listOf(Candidate(res.content))
            }
        }

        override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
        {
            if (args.size < 2) return false
            @Suppress("UNCHECKED_CAST")
            val config = ConfigLoader.getConfigLoader(args[0]) as? ConfigLoader<Any> ?: return false
            val rootMap = (showJson.encodeToJsonElement(showJson.serializersModule.serializer(config.type), config.config) as JsonObject).toMutableMap()
            var map: Any = rootMap
            for (i in 1 until args.size - 2) map = when (map)
            {
                is MutableMap<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    map as MutableMap<String, JsonElement>
                    when (val new0 = map[args[i]])
                    {
                        is JsonObject -> new0.toMutableMap().also { map[args[i]] = JsonObject(it) }
                        is JsonArray -> new0.toMutableList().also { map[args[i]] = JsonArray(it) }
                        else -> return false
                    }
                }
                is MutableList<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    map as MutableList<JsonElement>
                    when (val new0 = map.getOrNull(args[i].toIntOrNull() ?: return false))
                    {
                        is JsonObject -> new0.toMutableMap().also { map[args[i].toInt()] = JsonObject(it) }
                        is JsonArray -> new0.toMutableList().also { map[args[i].toInt()] = JsonArray(it) }
                        else -> return false
                    }
                }
                else -> return false
            }

            fun setValue(value: JsonElement): Boolean
            {
                val i = args.size - 2
                when (map)
                {
                    is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        map as MutableMap<String, JsonElement>
                        map[args[i]] = value
                    }
                    is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        map as MutableList<JsonElement>
                        val index = args[i].toIntOrNull() ?: return false
                        if (index < map.size) map[index] = value
                        else if (index == map.size) map.add(value)
                        else return false
                    }
                    else -> return false
                }
                return true
            }

            runCatching {
                setValue(showJson.decodeFromString<JsonElement>(args[args.size - 1]))
                config.setValue(showJson.decodeFromJsonElement(showJson.serializersModule.serializer(config.type), JsonObject(rootMap))!!)
                config.saveConfig()
            }.onFailure {
                setValue(JsonPrimitive(args[args.size - 1]))
                config.setValue(showJson.decodeFromJsonElement(showJson.serializersModule.serializer(config.type), JsonObject(rootMap))!!)
                config.saveConfig()
            }
            sender.out("Set.")
            return true
        }
    }

    object Get: Command
    {
        override val description: String get() = "Get config."

        override val args: String get() = "<config> <path>..."

        override suspend fun tabComplete(args: List<String>): List<Candidate>
        {
            if (args.size <= 1) return ConfigLoader.configs().map(::Candidate)
            val config = ConfigLoader.getConfigLoader(args[0]) ?: return emptyList()
            var res: JsonElement = showJson.encodeToJsonElement(showJson.serializersModule.serializer(config.type), config.config)
            for (i in 1 until args.size - 1) res = when (res)
            {
                is JsonObject -> res[args[i]] ?: return emptyList()
                is JsonArray -> res.getOrNull(args[i].toIntOrNull() ?: return emptyList()) ?: return emptyList()
                else -> return emptyList()
            }
            return when (res)
            {
                is JsonObject -> res.keys.map(::Candidate)
                is JsonArray -> res.indices.map { it.toString() }.map(::Candidate)
                else -> emptyList()
            }
        }

        override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
        {
            if (args.isEmpty())
            {
                val map = JsonObject(
                    ConfigLoader.configs().associate {
                        it to ConfigLoader.getConfigLoader(it)!!.let { config ->
                            showJson.encodeToJsonElement(showJson.serializersModule.serializer(config.type), config.config)
                        }
                    }
                )
                sender.out(showJson.encodeToString(map))
                return true
            }
            @Suppress("UNCHECKED_CAST")
            val config = ConfigLoader.getConfigLoader(args[0]) as? ConfigLoader<Any> ?: return false
            if (args.size == 1)
            {
                sender.out(showJson.encodeToString(showJson.serializersModule.serializer(config.type), config.config))
                return true
            }
            var obj = showJson.encodeToJsonElement(showJson.serializersModule.serializer(config.type), config.config)
            for (i in 1 until args.size)
            {
                obj = when (obj)
                {
                    is JsonObject -> obj[args[i]] ?: return false
                    is JsonArray -> obj.getOrNull(args[i].toIntOrNull() ?: return false) ?: return false
                    else -> return false
                }
            }
            sender.out(showJson.encodeToString(obj))
            return true
        }
    }

    object Reload: Command
    {
        override val description = "Reload configs."

        override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
        {
            runCatching()
            {
                if (args.isEmpty()) ConfigLoader.reloadAll()
                else if (args.size == 1) ConfigLoader.reload(args[0])
                else return false
            }.onFailure()
            { e ->

                sender.err("Failed to reload config: ")
                e.stackTraceToString().split("\n").forEach { sender.err(it) }
            }
            sender.out("Reloaded.")
            return true
        }

        override suspend fun tabComplete(args: List<String>): List<Candidate> = ConfigLoader.configs().map(::Candidate)
    }
}