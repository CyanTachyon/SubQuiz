package moe.tachyon.quiz.utils

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson

val ktorClientEngineFactory = CIO

val httpClient = HttpClient(ktorClientEngineFactory)
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