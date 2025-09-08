package moe.tachyon.quiz.utils

import io.ktor.http.ContentType
import io.ktor.http.fromFilePath
import io.ktor.util.encodeBase64
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.dataDir
import moe.tachyon.quiz.plugin.contentNegotiation.dataJson
import moe.tachyon.quiz.utils.ChatFiles.FileInfo.Companion.toDataUrl
import moe.tachyon.quiz.utils.ai.chat.tools.AiTools
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object AiLibraryFiles
{
    /**
     * AI答疑资料库
     */
    val aiLibrary = File(dataDir, "ai-library")
    val bdfzLibrary = File(aiLibrary, "bdfz")
    val booksLibrary = File(aiLibrary, "books")

    init
    {
        aiLibrary.mkdirs()
        bdfzLibrary.mkdirs()
        booksLibrary.mkdirs()
    }


    sealed class FileInfo(val file: File)
    {
        class NormalFile(file: File): FileInfo(file)
        {
            val name: String = file.name
            val content: String by lazy { file.readText() }
        }

        class Directory(file: File, val files: List<FileInfo>): FileInfo(file)
        {
            val name: String = file.name
        }
    }

    fun getAiLibraryFiles(ignoreEmpty: Boolean, fileExtensions: Set<String>? = null): List<FileInfo>
    {
        val files = mutableListOf<FileInfo>()
        fun getFilesRecursively(dir: File): List<FileInfo>
        {
            val fileList = mutableListOf<FileInfo>()
            dir.listFiles()?.forEach()
            { file ->
                if (file.isDirectory)
                {
                    val subFiles = getFilesRecursively(file)
                    if (!ignoreEmpty || subFiles.isNotEmpty())
                        fileList.add(FileInfo.Directory(file, subFiles))
                }
                else if ((fileExtensions == null || file.extension in fileExtensions) && (!ignoreEmpty || file.length() > 0))
                {
                    fileList.add(FileInfo.NormalFile(file))
                }
            }
            return fileList
        }
        if (aiLibrary.exists() && aiLibrary.isDirectory)
        {
            files.addAll(getFilesRecursively(aiLibrary))
        }
        return files
    }

    fun getAiLibraryFileText(filePath: String): String?
    {
        val file = File(aiLibrary, filePath.removePrefix("/"))
        if (!file.exists() || !file.isFile) return null
        if (!file.canonicalPath.startsWith(aiLibrary.canonicalPath)) return null
        val txt = file.readText()
        return "```path: $filePath```\n\n$txt"
    }

    fun getAiLibraryFileBytes(filePath: String): ByteArray?
    {
        val file = File(aiLibrary, filePath.removePrefix("/"))
        if (!file.exists() || !file.isFile) return null
        if (!file.canonicalPath.startsWith(aiLibrary.canonicalPath)) return null
        return file.readBytes()
    }

    fun getBookSubjects(): List<String>
    {
        if (!booksLibrary.exists() || !booksLibrary.isDirectory) return emptyList()
        return booksLibrary.listFiles()?.map { it.name }?.filterNotNull() ?: emptyList()
    }
}

@OptIn(ExperimentalUuidApi::class)
object ChatFiles
{
    /**
     * 聊天记录文件夹
     */
    val chatFiles = File(dataDir, "chat_files")

    init
    {
        chatFiles.mkdirs()
    }

    @Serializable
    data class FileInfo(
        val name: String,
        val type: AiTools.ToolData.Type
    )
    {
        val mimeType get() = ContentType.fromFilePath(name).firstOrNull()
        companion object
        {
            fun Pair<FileInfo, ByteArray>.toDataUrl(): String = "data:${first.mimeType};base64,${second.encodeBase64()}"
        }
    }

    fun parseUrl(chat: ChatId, url: String): String? =
        if (url.startsWith("uuid:", true))
            getChatFile(chat, Uuid.parseHex(url.removePrefix("uuid:")))?.toDataUrl()
        else if (url.startsWith("output:", true))
            File(sandboxOutputDir(chat), url.removePrefix("output:").removePrefix("/"))
                .takeIf { it.exists() && it.isFile }
                ?.let { FileInfo(it.name, AiTools.ToolData.Type.FILE) to it.readBytes() }
                ?.toDataUrl()
        else url

    fun getChatFilesDir(chat: ChatId): File = File(chatFiles, chat.toString()).apply(File::mkdirs)
    fun sandboxOutputDir(chat: ChatId): File = File(getChatFilesDir(chat), ".docker_out").apply(File::mkdirs)
    fun sandboxImageFile(chat: ChatId): File = File(getChatFilesDir(chat), ".code-runner-container.tar")

    fun deleteChatFiles(chat: ChatId)
    {
        val dir = File(chatFiles, chat.toString())
        if (dir.exists()) require(dir.deleteRecursively())
    }

    fun addChatFile(chat: ChatId, fileName: String, type: AiTools.ToolData.Type, data: ByteArray): Uuid
    {
        val dir = File(chatFiles, chat.toString()).apply(File::mkdirs)
        val uuid = Uuid.random()
        val infoFile = File(dir, uuid.toHexString() + ".info")
        val dataFile = File(dir, uuid.toHexString() + ".data")
        dataFile.writeBytes(data)
        infoFile.writeText(dataJson.encodeToString(FileInfo(fileName, type)))
        return uuid
    }

    fun getChatFile(chat: ChatId, uuid: Uuid): Pair<FileInfo, ByteArray>?
    {
        val dir = File(chatFiles, chat.toString()).apply(File::mkdirs)
        val infoFile = File(dir, uuid.toHexString() + ".info")
        val dataFile = File(dir, uuid.toHexString() + ".data")
        if (!infoFile.exists() || !dataFile.exists()) return null
        val data = dataFile.readBytes()
        val info = dataJson.decodeFromString<FileInfo>(infoFile.readText())
        return info to data
    }

    fun deleteChatFile(chat: ChatId, uuid: Uuid): Boolean
    {
        val dir = File(chatFiles, chat.toString())
        val infoFile = File(dir, uuid.toHexString() + ".info")
        val dataFile = File(dir, uuid.toHexString() + ".data")
        var success = true
        if (infoFile.exists()) success = success && infoFile.delete()
        if (dataFile.exists()) success = success && dataFile.delete()
        return success
    }

    fun getFiles(chat: ChatId): List<Uuid>
    {
        val dir = File(chatFiles, chat.toString())
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.mapNotNull()
        {
            val name = it.name
            if (name.endsWith(".info") || name.endsWith(".data"))
            {
                val uuidStr = name.removeSuffix(".info").removeSuffix(".data")
                return@mapNotNull runCatching { Uuid.parseHex(uuidStr) }.getOrNull()
            }
            return@mapNotNull null
        }?.distinct() ?: emptyList()
    }

    fun getUsedChatFileSize(chat: Chat): Long
    {
        val files = getFiles(chat.id).toSet()
        val str = dataJson.encodeToString(chat)
        val usedFiles = files.filter { str.contains(it.toHexString(), ignoreCase = true) }.toSet()
        var size = 0L
        usedFiles.forEach()
        {
            val f = getChatFile(chat.id, it)
            if (f != null) size += f.second.size
        }
        return size
    }

    fun clearUnusedChatFiles(chat: Chat)
    {
        val files = getFiles(chat.id).toSet()
        val str = dataJson.encodeToString(chat)
        val usedFiles = files.filter { str.contains(it.toHexString(), ignoreCase = true) }.toSet()
        val unusedFiles = files - usedFiles
        unusedFiles.forEach { deleteChatFile(chat.id, it) }
    }

    fun getChatFileSize(chat: ChatId): Long
    {
        val dir = File(chatFiles, chat.toString())
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walk().filter { it.isFile }.filter { it.extension == "data" }.map { it.length() }.sum()
    }
}