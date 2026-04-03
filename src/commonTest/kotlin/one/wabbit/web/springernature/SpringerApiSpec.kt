package one.wabbit.web.springernature

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpringerApiSpec {
    @Test
    fun `searchByTitle applies config and emits article records`() = runTest {
        val api = KtorSpringerNatureApi(
            httpClient = testClient { request ->
                assertEquals("http", request.url.protocol.name)
                assertEquals("api.springernature.com", request.url.host)
                assertEquals("/openaccess/json", request.url.encodedPath)
                assertEquals("title:\"kotlin\"", request.url.parameters["q"])
                assertEquals("secret-key", request.url.parameters["api_key"])

                respond(
                    content = """
                        {
                          "query": "title:\"kotlin\"",
                          "result": [
                            {
                              "total": 1,
                              "start": 1,
                              "pageLength": 10,
                              "recordsDisplayed": 1
                            }
                          ],
                          "records": [
                            {
                              "identifier": "doi:10.1000/example",
                              "title": "Kotlin in Practice",
                              "publicationName": "Journal of Kotlin",
                              "publisher": "Springer Nature",
                              "doi": "10.1000/example",
                              "publicationDate": "2025-01-01",
                              "abstract": "A practical paper.",
                              "future_field": "ignored"
                            }
                          ]
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
            config = SpringerNatureApi.Config(apiKey = "secret-key"),
        )

        val records = api.searchByTitle("kotlin").toList()

        assertEquals(1, records.size)
        assertEquals("Kotlin in Practice", records.single().title)
        assertEquals("10.1000/example", records.single().doi)
    }

    @Test
    fun `searchByTitle rejects blank title`() = runTest {
        val api = KtorSpringerNatureApi(
            httpClient = testClient {
                error("request should not be made for invalid input")
            },
            config = SpringerNatureApi.Config(apiKey = "secret-key"),
        )

        val error = assertFailsWith<SpringerApiError.InvalidInput> {
            api.searchByTitle("   ").toList()
        }

        assertContains(error.message.orEmpty(), "title must not be blank")
    }

    @Test
    fun `search maps non-success responses to http error`() = runTest {
        val api = KtorSpringerNatureApi(
            httpClient = testClient {
                respond(
                    content = "forbidden",
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                )
            },
            config = SpringerNatureApi.Config(apiKey = "secret-key"),
        )

        val error = assertFailsWith<SpringerApiError.Http> {
            api.search("kotlin")
        }

        assertEquals(403, error.status)
        assertContains(error.bodySample.orEmpty(), "forbidden")
    }

    private fun testClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        install(HttpTimeout)
    }
}
