package cn.org.subit.console.command

import cn.org.subit.config.ConfigLoader
import cn.org.subit.plugin.contentNegotiation.showJson
import kotlinx.serialization.serializer
import net.mamoe.yamlkt.*
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
            var res: Any = Yaml.decodeYamlMapFromString(Yaml.encodeToString(serializer(config.type), config.config))
            for (i in 1 until args.size - 1) res = (res as? YamlMap)?.get(args[i]) ?: return emptyList()
            return when (res)
            {
                is YamlMap -> res.keys.mapNotNull { it.asPrimitiveOrNull()?.content }.map(::Candidate)
                is YamlList -> res.indices.map { it.toString() }.map(::Candidate)
                else -> listOf(Candidate(res.toString()))
            }
        }

        override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
        {
            if (args.size < 2) return false
            @Suppress("UNCHECKED_CAST")
            val config = ConfigLoader.getConfigLoader(args[0]) as? ConfigLoader<Any> ?: return false
            val rootMap = Yaml.decodeYamlMapFromString(Yaml.encodeToString(serializer(config.type), config.config)).toContentMap().toMutableMap()
            var yamlMap = rootMap
            for (i in 1 until args.size - 2)
            {
                @Suppress("UNCHECKED_CAST")
                val newMap = (yamlMap[args[i]] as? Map<String?, Any?>)?.toMutableMap() ?: return false
                yamlMap[args[i]] = newMap
                yamlMap = newMap
            }
            yamlMap[args[args.size - 2]] = args[args.size - 1]
            val res = YamlMap(rootMap)
            config.setValue(Yaml.decodeFromString(serializer(config.type), Yaml.encodeToString(res))!!)
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
            var res: Any = Yaml.decodeYamlMapFromString(Yaml.encodeToString(serializer(config.type), config.config))
            for (i in 1 until args.size - 1) res = (res as? YamlMap)?.get(args[i]) ?: return emptyList()
            return when (res)
            {
                is YamlMap -> res.keys.mapNotNull { it.asPrimitiveOrNull()?.content }.map(::Candidate)
                is YamlList -> res.indices.map { it.toString() }.map(::Candidate)
                else -> emptyList()
            }
        }

        override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
        {
            if (args.isEmpty()) return false
            @Suppress("UNCHECKED_CAST")
            val config = ConfigLoader.getConfigLoader(args[0]) as? ConfigLoader<Any> ?: return false
            if (args.size == 1)
            {
                sender.out(showJson.encodeToString(showJson.serializersModule.serializer(config.type), config.config))
                return true
            }
            var yamlMap = Yaml.decodeYamlMapFromString(Yaml.encodeToString(serializer(config.type), config.config))
            for (i in 1 until args.size - 1)
            {
                yamlMap = yamlMap[args[i]] as? YamlMap ?: return false
            }
            sender.out(showJson.encodeToString(yamlMap[args.last()]))
            return true
        }
    }
}