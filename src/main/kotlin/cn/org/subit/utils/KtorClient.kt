package cn.org.subit.utils

import cn.org.subit.plugin.contentNegotiation.contentNegotiationJson
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers

val httpClient = HttpClient(CIO)
{
    engine()
    {
        dispatcher = Dispatchers.IO
        requestTimeout = 1_000
    }
    install(ContentNegotiation)
    {
        json(contentNegotiationJson)
    }
}