package one.wabbit.web.springernature

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SpringerNature {
    // https://github.com/JayQuantRise20/springer-nature-api
    // https://github.com/faramer86/NatureSpringerBot
    // https://github.com/oudeng/API_XML_SpringNature
    // https://github.com/DivyaPai03/LiteSpringer-App
    // https://github.com/snawarhussain/LiteratureReviewGeneator
}

class SpringerAPI(
    private val apiKey: String,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) { json() }

        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 30000
        }

        defaultRequest {
            accept(ContentType.Application.Json)
            header("Accept-Charset", "UTF-8")
            header("User-Agent", "Springer API Client/1.0")
        }
    },
) {
    companion object {
        private const val SPRINGER_API = "http://api.springernature.com/openaccess/json"
    }

    /**
     * Search for articles by title
     *
     * @param title The article title to search for
     * @return Flow of SteamResult containing ArticleData or error
     */
    fun searchByTitle(title: String): Flow<ArticleData> = flow {
        val response =
            client
                .get(SPRINGER_API) {
                    parameter("q", "title:\"$title\"")
                    parameter("api_key", apiKey)
                }
                .bodyAsText()

        println(response)

        val searchData = Json.decodeFromString<SearchResponse>(response)

        searchData.records.forEach { article -> emit(article) }
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
