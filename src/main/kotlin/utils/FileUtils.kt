package moe.tachyon.quiz.utils

import io.ktor.http.ContentType
import io.ktor.http.fromFilePath
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.dataDir
import moe.tachyon.quiz.plugin.contentNegotiation.dataJson
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
    }

    fun deleteChatFiles(chat: ChatId)
    {
        val dir = File(chatFiles, chat.toString())
        if (dir.exists()) require(dir.deleteRecursively())
    }

    @OptIn(ExperimentalUuidApi::class)
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

    @OptIn(ExperimentalUuidApi::class)
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
}