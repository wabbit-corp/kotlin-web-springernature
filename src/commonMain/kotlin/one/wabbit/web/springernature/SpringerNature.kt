package one.wabbit.web.springernature

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import one.wabbit.web.common.Etiquette
import one.wabbit.web.common.Timeouts
import one.wabbit.web.common.applyEtiquette
import one.wabbit.web.common.applyTimeouts
import one.wabbit.web.common.retryingHttpCall
import one.wabbit.web.common.safeBodyPrefix
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

sealed class SpringerApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidInput(message: String) : SpringerApiError(message)

    class Http(
        val url: String,
        val status: Int,
        val bodySample: String?,
        cause: Throwable? = null,
    ) : SpringerApiError(buildString {
        append("HTTP ")
        append(status)
        append(" from ")
        append(url)
        if (!bodySample.isNullOrBlank()) {
            append(", body sample: ")
            append(bodySample.take(256))
        }
    }, cause)

    class Network(
        val url: String,
        cause: Throwable,
    ) : SpringerApiError(
        "Network failure talking to $url: ${cause::class.simpleName}: ${cause.message}",
        cause,
    )

    class Parse(
        val url: String,
        cause: Throwable,
    ) : SpringerApiError(
        "Failed to parse Springer Nature response from $url: ${cause::class.simpleName}: ${cause.message}",
        cause,
    )
}

interface SpringerNatureApi {
    data class Config(
        val apiKey: String,
        val baseUrl: String = "http://api.springernature.com/openaccess/json",
        val etiquette: Etiquette = Etiquette("one.wabbit.web.springernature/1.0"),
        val timeouts: Timeouts = Timeouts(
            request = 30.seconds,
            connect = 30.seconds,
            socket = 30.seconds,
        ),
    ) {
        init {
            require(apiKey.isNotBlank()) { "apiKey must not be blank" }
            require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        }
    }

    fun searchByTitle(title: String): Flow<ArticleData>
}

class KtorSpringerNatureApi(
    val httpClient: HttpClient,
    val config: SpringerNatureApi.Config,
) : SpringerNatureApi {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    init {
        check(runCatching { httpClient.pluginOrNull(HttpTimeout) }.getOrNull() != null) {
            "HttpTimeout plugin must be installed on the provided HttpClient for per-request timeouts to work."
        }
    }

    /**
     * Search for articles by title
     *
     * @param title The article title to search for.
     * @return Flow of matching ArticleData items.
     */
    override fun searchByTitle(title: String): Flow<ArticleData> = flow {
        val searchData = search(title)

        searchData.records.forEach { article -> emit(article) }
    }

    suspend fun search(title: String): SearchResponse {
        if (title.isBlank()) {
            throw SpringerApiError.InvalidInput("title must not be blank")
        }

        val response = try {
            retryingHttpCall {
                httpClient.get(config.baseUrl) {
                    expectSuccess = true
                    applyEtiquette(config.etiquette)
                    applyTimeouts(config.timeouts)
                    accept(ContentType.Application.Json)
                    parameter("q", "title:\"$title\"")
                    parameter("api_key", config.apiKey)
                }
            }
        } catch (t: Throwable) {
            throw t.toSpringerError(config.baseUrl)
        }

        return response.decodeSearchResponse(config.baseUrl)
    }

    private suspend fun HttpResponse.decodeSearchResponse(url: String): SearchResponse {
        if (!status.isSuccess()) {
            val sample = runCatching { safeBodyPrefix(2048) }.getOrNull()
            throw SpringerApiError.Http(url, status.value, sample)
        }

        val body = try {
            bodyAsText()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw SpringerApiError.Network(url, t)
        }

        return try {
            json.decodeFromString<SearchResponse>(body)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw SpringerApiError.Parse(url, t)
        }
    }
}

typealias SpringerAPI = KtorSpringerNatureApi

private suspend fun Throwable.toSpringerError(url: String): SpringerApiError {
    if (this is CancellationException) throw this
    return if (this is ResponseException) {
        val sample = runCatching { response.safeBodyPrefix(2048) }.getOrNull()
        SpringerApiError.Http(url, response.status.value, sample, this)
    } else {
        SpringerApiError.Network(url, this)
    }
}

@Serializable
data class SearchResponse(
    val query: String,
    val apiKey: String = "",
    val result: List<ResultData>,
    @SerialName("records") val records: List<ArticleData>,
)

@Serializable
data class ResultData(
    val total: Int,
    val start: Int,
    val pageLength: Int,
    @SerialName("recordsDisplayed") val recordsDisplayed: Int,
)

@Serializable
data class ArticleData(
    val identifier: String? = null,
    val title: String,
    val publicationName: String,
    val publisher: String,
    val doi: String,
    val publicationDate: String,
    val abstract: String? = null,
    val creators: List<Creator>? = null,
    val url: List<ArticleUrl>? = null,
)

@Serializable data class Creator(val creator: String)

@Serializable data class ArticleUrl(val format: String, val value: String)
