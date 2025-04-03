package cn.org.subit.utils

import cn.org.subit.config.cosConfig
import cn.org.subit.dataClass.SectionId
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

    private fun getObjects(folder: String): List<String> =
        cosClient.listObjects(cosConfig.bucketName, folder).objectSummaries.map { it.key }.filterNot { it.endsWith('/') }

    /**
     * 给某个题目添加图片
     * @param sectionId 题目ID
     * @param md5 图片的MD5值
     * @param type 图片的类型
     * @return 若需要上传, 返回图片的URL, 否则返回null
     */
    suspend fun addImage(sectionId: SectionId, md5: String, type: ContentType): String? = withContext(Dispatchers.IO)
    {
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

    fun removeImage(sectionId: SectionId, md5: String) =
        deleteObject("/section_images/$sectionId/$md5")

    fun getImages(sectionId: SectionId): List<String> =
        getObjects("/section_images/$sectionId/")
}