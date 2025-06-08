package cn.org.subit.utils

import cn.org.subit.plugin.contentNegotiation.contentNegotiationJson
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers

val httpClient = HttpClient(Java)
{
    engine()
    {
        pipelining = true
        dispatcher = Dispatchers.IO
        protocolVersion = java.net.http.HttpClient.Version.HTTP_2
    }
    install(ContentNegotiation)
    {
        json(contentNegotiationJson)
    }
    install(SSE)
}

val sseClient = HttpClient(Java)
{
    engine()
    {
        pipelining = true
        dispatcher = Dispatchers.IO
        protocolVersion = java.net.http.HttpClient.Version.HTTP_1_1
    }
    install(SSE)
}