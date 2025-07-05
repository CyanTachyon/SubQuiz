package moe.tachyon.quiz.config

import kotlinx.serialization.Serializable

@Serializable
data class TencentCOS(
    val bucketName: String = "your bucket name",
    val region: String = "ap-beijing",
    val secretId: String = "your secret id",
    val secretKey: String = "your secret key",
    val cdnUrl: String = "https://your-cdn-url.com/",
)

val cosConfig: TencentCOS by config("tencentCOS.yml", TencentCOS())