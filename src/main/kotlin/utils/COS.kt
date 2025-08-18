package moe.tachyon.quiz.utils

import com.qcloud.cos.COSClient
import com.qcloud.cos.ClientConfig
import com.qcloud.cos.auth.BasicCOSCredentials
import com.qcloud.cos.http.HttpMethodName
import com.qcloud.cos.model.GeneratePresignedUrlRequest
import com.qcloud.cos.model.ObjectMetadata
import com.qcloud.cos.region.Region
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.tachyon.quiz.config.cosConfig
import moe.tachyon.quiz.dataClass.SectionId
import moe.tachyon.quiz.logger.SubQuizLogger
import org.koin.core.component.KoinComponent
import java.io.InputStream
import java.util.*

object COS: KoinComponent
{
    private val cosClient
        get() = COSClient(
            BasicCOSCredentials(cosConfig.secretId, cosConfig.secretKey),
            ClientConfig(Region(cosConfig.region))
        )

    private val logger = SubQuizLogger.getLogger<COS>()

    private fun putObject(filename: String, contentType: ContentType, contentLength: Long, obj: InputStream): String =
        cosClient.putObject(
            cosConfig.bucketName,
            filename,
            obj,
            ObjectMetadata().apply {
                this.contentType = contentType.toString()
                this.contentLength = contentLength
            }).contentMd5!!

    private fun deleteObject(filename: String) =
        cosClient.deleteObject(cosConfig.bucketName, filename)

    private fun hasObject(filename: String): Boolean =
        cosClient.doesObjectExist(cosConfig.bucketName, filename)

    private fun getObject(filename: String): InputStream =
        cosClient.getObject(cosConfig.bucketName, filename).objectContent

    private fun getObjects(folder: String): List<String>
    {
        val folder = folder.trim('/') + '/'
        return cosClient
            .listObjects(cosConfig.bucketName, folder)
            .objectSummaries
            .mapNotNull { it.key.substring(folder.length).takeIf { k -> '/' !in k } }
    }

    fun getImageUrl(sectionId: SectionId, md5: String): String = "${cosConfig.cdnUrl}/section_images/$sectionId/$md5"

    /**
     * 给某个题目添加图片
     * @param sectionId 题目ID
     * @param md5 图片的MD5值
     * @param type 图片的类型
     * @return 若需要上传, 返回图片的URL, 否则返回null
     */
    suspend fun addImage(sectionId: SectionId, md5: String, type: ContentType): Pair<String?, String> = withContext(Dispatchers.IO)
    {
        val filename = md5.decodeBase64Bytes().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        if (hasObject("/section_images/$sectionId/$filename")) return@withContext null to filename
        val url = cosClient.generatePresignedUrl(
            GeneratePresignedUrlRequest(
                cosConfig.bucketName,
                "/section_images/$sectionId/$filename"
            ).apply {
                withMethod(HttpMethodName.PUT)
                withContentType(type.toString())
                withContentMd5(md5)
                withExpiration(Date(System.currentTimeMillis() + 30 * 60 * 1000))
            }
        )
        return@withContext url.toString() to filename
    }

    fun removeImage(sectionId: SectionId, md5: String)
    {
        deleteObject("/section_images/$sectionId/$md5")
        runCatching { deleteObject("/section_images/$sectionId/$md5.md") }
    }

    fun getImages(sectionId: SectionId): List<String> = getObjects("section_images/$sectionId/")

    fun hasImage(sectionId: SectionId, md5: String): Boolean = hasObject("section_images/$sectionId/$md5")

    fun putImageDescription(
        sectionId: SectionId,
        md5: String,
        description: String
    )
    {
        val filename = "/section_images/$sectionId/$md5"

        if (!hasImage(sectionId, md5)) error("图片不存在: $filename")

        val bytes = description.toByteArray(Charsets.UTF_8)

        putObject(
            "$filename.md",
            ContentType.Text.Plain,
            bytes.size.toLong(),
            bytes.inputStream()
        )
    }

    fun getImageDescription(
        sectionId: SectionId,
        md5: String
    ): String?
    {
        val filename = "/section_images/$sectionId/$md5.md"

        if (!hasImage(sectionId, md5)) return null
        if (!hasObject(filename)) return null

        return logger.warning("获取图片描述失败:")
        {
            getObject(filename).use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrNull()
    }
}