package moe.tachyon.quiz.config

import kotlinx.serialization.Serializable

@Serializable
data class ApiDocsConfig(val name: String = "username", val password: String = "password")

val apiDocsConfig: ApiDocsConfig by config("api_docs.yml", ApiDocsConfig())