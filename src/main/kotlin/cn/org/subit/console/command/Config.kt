package cn.org.subit.console.command

import cn.org.subit.config.ConfigLoader
import cn.org.subit.plugin.contentNegotiation.showJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import org.jline.reader.Candidate

object Config: TreeCommand(Get, Set)
{
    override val description: String get() = "Get/Set config."

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

        override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
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
            }.onFailure {
                setValue(JsonPrimitive(args[args.size - 1]))
                config.setValue(showJson.decodeFromJsonElement(showJson.serializersModule.serializer(config.type), JsonObject(rootMap))!!)
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

        override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
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
}