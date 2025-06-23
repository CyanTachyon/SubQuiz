package moe.tachyon.quiz.config

import moe.tachyon.quiz.console.ColorDisplayMode
import moe.tachyon.quiz.console.Console
import moe.tachyon.quiz.console.EffectDisplayMode
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.logger.ToFileHandler
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.mamoe.yamlkt.Comment
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.regex.Pattern
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@Serializable
data class LoggerConfig(
    @Comment("过滤器，根据whiteList决定符合哪些条件的log会被打印/过滤")
    val matchers: List<String>,
    @Comment("是否为白名单模式，如果为true，则只有符合matchers的log会被打印，否则只有不符合matchers的log会被打印")
    val whiteList: Boolean,
    @Comment("日志等级 (OFF, SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST, ALL)")
    @SerialName("level")
    val levelName: String,
    @Comment("是否在日志中显示日志名称")
    val showLoggerName: Boolean,
    @Comment("日志的颜色样式, 可选值: RGB, SIMPLE, NONE")
    val color: ColorDisplayMode,
    @Comment("是否在日志中使用样式(加粗, 斜体, 下划线等)")
    val effect: Boolean,
    @Comment("日志文件保存时间, 超过该时间的日志会被删除, 格式为ISO8601")
    val logFileSaveTime: Duration
)
{
    @Transient
    val level: Level = Level.parse(levelName)

    @Transient
    val pattern: Pattern = Pattern.compile(matchers.joinToString("|") { "($it)" })

    fun check(record: LogRecord): Boolean = (matchers.isEmpty() || pattern.matcher(record.message).find() == whiteList)
}

var loggerConfig: LoggerConfig by config(
    "logger.yml",
    LoggerConfig(listOf(), true, "INFO", false, ColorDisplayMode.RGB, true, 7.days),
    { _, new ->
        SubQuizLogger.globalLogger.logger.setLevel(new.level)
        Console.ansiEffectMode = if (new.effect) EffectDisplayMode.ON else EffectDisplayMode.OFF
        Console.ansiColorMode = new.color
        ToFileHandler.clearOld(new.logFileSaveTime)
    }
)