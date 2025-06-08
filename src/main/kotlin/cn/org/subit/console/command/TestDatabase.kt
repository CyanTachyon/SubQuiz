package cn.org.subit.console.command

import cn.org.subit.console.SimpleAnsiColor.Companion.RED
import cn.org.subit.console.command.TestDatabase.toStr
import cn.org.subit.database.*
import cn.org.subit.debug
import cn.org.subit.logger.SubQuizLogger
import cn.org.subit.plugin.contentNegotiation.contentNegotiationJson
import cn.org.subit.plugin.contentNegotiation.showJson
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import org.jline.reader.Candidate
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberFunctions

object TestDatabase: Command, KoinComponent
{
    private val logger = SubQuizLogger.getLogger<TestDatabase>()
    override val description: String = "Call the database interface. ${RED.bright()}This command is for debugging only."
    override val args: String = "<table> <method> [args]..."
    override val aliases: List<String> = listOf("database", "db")
    override val log: Boolean get() = debug

    private inline fun <reified T: Any> dao() = get<T>() to T::class
    private val database: Map<String, Pair<Any, KClass<*>>> by lazy()
    {
        mapOf(
            "Exams" to dao<Exams>(),
            "Histories" to dao<Histories>(),
            "KnowledgePoints" to dao<KnowledgePoints>(),
            "Permissions" to dao<Permissions>(),
            "Preference" to dao<Preferences>(),
            "PreparationGroups" to dao<PreparationGroups>(),
            "Quizzes" to dao<Quizzes>(),
            "Records" to dao<Records>(),
            "Sections" to dao<Sections>(),
            "SectionTypes" to dao<SectionTypes>(),
            "Subjects" to dao<Subjects>(),
            "Users" to dao<Users>(),
        )
    }

    /**
     * 将某个对象转为字符串
     * - 若为基础类型直接转为字符串
     * - 内联值类则将其值转为字符串
     * - 单例类则将其类名转为字符串
     */
    @OptIn(InternalSerializationApi::class)
    private fun <T> toStr(any: T, type: KType): String = when (any)
    {
        null                                                                         -> "null"
        is Int, is Long, is Short, is Byte, is Float, is Double, is Char, is Boolean -> any.toString()
        is String                                                                    -> "\"$any\""
        is Enum<*>                                                                   -> any.name
        else                                                                         ->
        {
            if (any::class.isValue)
            {
                val property = any::class.declaredMemberProperties.first()
                val propertyType = property.getter.returnType
                val propertyValue = property.getter.call(any)
                toStr(propertyValue, propertyType)
            }
            else if (any::class.objectInstance != null) any::class.simpleName!!
            else
            {
                runCatching { showJson.encodeToString(serializer(type), any) }.getOrElse { any.toString() }
            }
        }
    }

    /**
     * 从字符串转为某个对象, 应和[toStr]对应
     */
    @OptIn(InternalSerializationApi::class)
    private fun fromStr(str: String, clazz: KClass<*>): Any? = if (str == "null") null
    else when (clazz)
    {
        Int::class     -> str.toInt()
        Long::class    -> str.toLong()
        Short::class   -> str.toShort()
        Byte::class    -> str.toByte()
        Float::class   -> str.toFloat()
        Double::class  -> str.toDouble()
        Char::class    -> str.single()
        Boolean::class -> str.toBoolean()
        String::class  -> str
        else           ->
        {
            if (clazz.isSubclassOf(Enum::class))
            {
                val enum = clazz.java.enumConstants.find { (it as Enum<*>).name == str }
                require(enum != null) { "Enum constant not found." }
                enum
            }
            else if (clazz.isValue)
            {
                require(clazz.declaredMemberProperties.size == 1) { "Value class should have only one property." }
                val property = clazz.declaredMemberProperties.first()
                val value = fromStr(str, property.returnType.classifier as KClass<*>)
                clazz.constructors.first().call(value)
            }
            else if (clazz.isData)
            {
                contentNegotiationJson.decodeFromString(clazz.serializer(), str)
            }
            else if (clazz.objectInstance != null) clazz.objectInstance!!
            else throw IllegalArgumentException("Unsupported class. $clazz")
        }
    }

    override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
    {
        if (!debug)
        {
            sender.err("此命令仅用于调试, 请在debug模式下使用.")
            sender.err("${RED}开启debug模式请在启动参数中加入 -debug=true")
            return true
        }

        if (args.size < 2) return false

        val (table, clazz) = database[args[0]] ?: return false
        val method = clazz.memberFunctions.find { it.name == args[1] } ?: return false

        val params: MutableMap<KParameter, Any?> = mutableMapOf()

        // 0是this
        params[method.parameters[0]] = table
        for (i in 2 until args.size)
        {
            // 注意计算 args从2开始的, 参数刨去了this是从1开始的, 所以这里是i-1
            val param = method.parameters[i - 1]
            val value = fromStr(args[i], param.type.classifier as KClass<*>)
            params[param] = value
        }

        val res = if (method.isSuspend) method.callSuspendBy(params)
        else method.callBy(params)
        sender.out("Result: ${toStr(res, method.returnType)}")
        return true
    }

    override suspend fun tabComplete(args: List<String>): List<Candidate>
    {
        return when (args.size)
        {
            1    -> database.keys
            2    -> database[args[0]]?.run {
                second.memberFunctions
                    .map { it.name }
                    .filter { it !in listOf("equals", "hashCode", "toString") }
            } ?: emptyList()

            else ->
            {
                val method = database[args[0]]?.run { second.memberFunctions.find { it.name == args[1] } }
                             ?: return emptyList()

                // -1 是 排除掉this, -2 是排除掉table和method
                if (method.parameters.size - 1 < args.size - 2)
                    return emptyList()

                // 注意计算, 例如第一个参数, 此时args.size是3, 而我们需要读取method.parameters[1](这里排除掉了this所以是1), 所以这里是-2
                listOf("<${method.parameters[args.size - 2].name}>")
            }
        }.map { Candidate(it) }
    }
}