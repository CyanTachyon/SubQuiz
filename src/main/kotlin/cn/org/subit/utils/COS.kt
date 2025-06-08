package cn.org.subit.utils

import cn.org.subit.config.cosConfig
import cn.org.subit.dataClass.ExamId
import cn.org.subit.dataClass.SectionId
import cn.org.subit.logger.SubQuizLogger
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

    private fun getObjects(folder: String): List<String> =
        cosClient
            .listObjects(cosConfig.bucketName, folder)
            .objectSummaries
            .map { it.key }
            .map { it.substring(folder.length) }
            .filterNot { '/' in it }

    fun getImageUrl(sectionId: SectionId, md5: String): String =
        if (sectionId.value < 0) getImageUrl(ExamId(-sectionId.value), md5)
        else "${cosConfig.cdnUrl}/section_images/$sectionId/$md5"

    fun getImageUrl(examId: ExamId, md5: String): String =
        if (examId.value < 0) getImageUrl(SectionId(-examId.value), md5)
        else "${cosConfig.cdnUrl}/exam_images/$examId/$md5"

    /**
     * 给某个题目添加图片
     * @param sectionId 题目ID
     * @param md5 图片的MD5值
     * @param type 图片的类型
     * @return 若需要上传, 返回图片的URL, 否则返回null
     */
    suspend fun addImage(sectionId: SectionId, md5: String, type: ContentType): String? = withContext(Dispatchers.IO)
    {
        if (sectionId.value < 0) return@withContext addImage(ExamId(-sectionId.value), md5, type)
        val filename = md5.decodeBase64Bytes().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        if (hasObject("/section_images/$sectionId/$filename")) return@withContext null
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
        return@withContext url.toString()
    }

    fun removeImage(sectionId: SectionId, md5: String)
    {
        if (sectionId.value < 0) return removeImage(ExamId(-sectionId.value), md5)
        deleteObject("/section_images/$sectionId/$md5")
        runCatching { deleteObject("/section_images/$sectionId/$md5.md") }
            .onFailure { logger.warning("删除图片描述失败: $it") }
    }

    fun getImages(sectionId: SectionId): List<String> =
        if (sectionId.value < 0) getImages(ExamId(-sectionId.value))
        else getObjects("/section_images/$sectionId/")

    fun hasImage(sectionId: SectionId, md5: String): Boolean =
        if (sectionId.value < 0) hasImage(ExamId(-sectionId.value), md5)
        else hasObject("/section_images/$sectionId/$md5")

    suspend fun addImage(examId: ExamId, md5: String, type: ContentType): String? = withContext(Dispatchers.IO)
    {
        if (examId.value < 0) return@withContext addImage(SectionId(-examId.value), md5, type)
        val filename = md5.decodeBase64Bytes().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        if (hasObject("/exam_images/$examId/$filename")) return@withContext null
        val url = cosClient.generatePresignedUrl(
            GeneratePresignedUrlRequest(
                cosConfig.bucketName,
                "/exam_images/$examId/$filename"
            ).apply {
                withMethod(HttpMethodName.PUT)
                withContentType(type.toString())
                withContentMd5(md5)
                withExpiration(Date(System.currentTimeMillis() + 30 * 60 * 1000))
            }
        )
        return@withContext url.toString()
    }

    fun removeImage(examId: ExamId, md5: String)
    {
        if (examId.value < 0) removeImage(SectionId(-examId.value), md5)
        deleteObject("/exam_images/$examId/$md5")
        runCatching { deleteObject("/exam_images/$examId/$md5.md") }
            .onFailure { logger.warning("删除图片描述失败: $it") }
    }

    fun getImages(examId: ExamId): List<String> =
        if (examId.value < 0) getImages(SectionId(-examId.value))
        else getObjects("/exam_images/$examId/")

    fun hasImage(examId: ExamId, md5: String): Boolean =
        if (examId.value < 0) hasImage(SectionId(-examId.value), md5)
        else hasObject("/exam_images/$examId/$md5")

    fun putImageDescription(
        sectionId: SectionId,
        md5: String,
        description: String
    )
    {
        val filename =
            if (sectionId.value >= 0) "/section_images/$sectionId/$md5"
            else "/exam_images/${-sectionId.value}/$md5"

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
        val filename =
            if (sectionId.value >= 0) "/section_images/$sectionId/$md5.md"
            else "/exam_images/${-sectionId.value}/$md5.md"

        if (!hasImage(sectionId, md5)) return null

        return runCatching {
            getObject(filename).use { String(it.readBytes(), Charsets.UTF_8) }
        }.onFailure { logger.warning("获取图片描述失败: $it") }.getOrNull()
    }
}