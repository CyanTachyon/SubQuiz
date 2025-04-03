package cn.org.subit.config

import kotlinx.serialization.Serializable

@Serializable
data class TencentCOS(
    val bucketName: String = "your bucket name",
    val region: String = "ap-beijing",
    val secretId: String = "your secret id",
    val secretKey: String = "your secret key",
)

var cosConfig: TencentCOS by config("tencentCOS.yml", TencentCOS())