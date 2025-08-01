package moe.tachyon.quiz.utils

import moe.tachyon.quiz.dataDir
import java.io.File

object FileUtils
{
    /**
     * AI答疑资料库
     */
    val aiLibrary = File(dataDir, "ai-library")

    init
    {
        aiLibrary.mkdirs()
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
        val file = File(aiLibrary, filePath)
        if (!file.exists() || !file.isFile) return null
        if (!file.canonicalPath.startsWith(aiLibrary.canonicalPath)) return null
        val txt = file.readText()
        return "```path: $filePath```\n\n$txt"
    }

    fun getAiLibraryFileBytes(filePath: String): ByteArray?
    {
        val file = File(aiLibrary, filePath)
        if (!file.exists() || !file.isFile) return null
        if (!file.canonicalPath.startsWith(aiLibrary.canonicalPath)) return null
        return file.readBytes()
    }
}