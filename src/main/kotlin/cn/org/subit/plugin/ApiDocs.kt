@file:Suppress("PackageDirectoryMismatch")

package cn.org.subit.plugin.apiDocs

import cn.org.subit.plugin.contentNegotiation.showJson
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.data.AuthKeyLocation
import io.github.smiley4.ktorswaggerui.data.AuthScheme
import io.github.smiley4.ktorswaggerui.data.AuthType
import io.github.smiley4.ktorswaggerui.data.KTypeDescriptor
import io.github.smiley4.schemakenerator.reflection.processReflection
import io.github.smiley4.schemakenerator.swagger.compileInlining
import io.github.smiley4.schemakenerator.swagger.data.TitleType
import io.github.smiley4.schemakenerator.swagger.generateSwaggerSchema
import io.github.smiley4.schemakenerator.swagger.withAutoTitle
import io.github.smiley4.schemakenerator.swagger.withTitle
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import kotlinx.serialization.serializer

/**
 * 在/api-docs 路径下安装SwaggerUI
 */
fun Application.installApiDoc() = install(SwaggerUI)
{
    info()
    {
        title = "SubQuiz后端API文档"
        version = cn.org.subit.version
        description = """
            SubQuiz后端API文档
            
            [进入终端](${this@installApiDoc.rootPath}/terminal)
        """.trimIndent()
    }
    this.ignoredRouteSelectors += RateLimitRouteSelector::class

    val serverUrl = this@installApiDoc.environment.config.propertyOrNull("serverUrl")

    val servers =
        if (serverUrl == null) emptyList()
        else runCatching { serverUrl.getList() }.getOrElse { listOf(serverUrl.getString()) }

    servers.forEach { server { url = it } }

    schemas {
        generator = {
            it.processReflection()
                .generateSwaggerSchema()
                .withTitle(TitleType.SIMPLE)
                .compileInlining()
        }
    }

    examples {
        encoder { type, example ->
            when (type)
            {
                is KTypeDescriptor -> showJson.encodeToString(showJson.serializersModule.serializer(type.type), example)
                else -> example
            }
        }
    }

    security {
        securityScheme("JWT")
        {
            name = "Authorization"
            scheme = AuthScheme.BEARER
            location = AuthKeyLocation.HEADER
            description = "JWT Token"
            bearerFormat = "Bearer <token>"
            type = AuthType.HTTP
        }
        defaultSecuritySchemeNames("JWT")
    }
}