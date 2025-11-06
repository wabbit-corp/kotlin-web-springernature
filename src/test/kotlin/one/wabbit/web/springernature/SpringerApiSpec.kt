package one.wabbit.web.springernature

import kotlin.test.Ignore
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class SpringerApiSpec {
    val apikey = "cee62c7d838a9d0fd1c7480e0b9e0182"

    @Ignore
    @Test
    fun main() {
        // FIXME: doesn't work
        runBlocking {
            val springerApi = SpringerAPI(apikey)
            springerApi.searchByTitle("kotlin").collect { book -> println("Found: ${book.title}") }
        }
    }
}
