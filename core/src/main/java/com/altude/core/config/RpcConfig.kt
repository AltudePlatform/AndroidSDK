import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

object RpcConfig {
    private lateinit var retrofit: Retrofit

//    @OptIn(ExperimentalSerializationApi::class)
//    fun initialize(rpcBaseUrl: String) {
//        val json = Json { ignoreUnknownKeys = true }
//        val logging = HttpLoggingInterceptor().apply {
//            level = HttpLoggingInterceptor.Level.BODY
//        }
//
//        val client = OkHttpClient.Builder()
//            .addInterceptor(logging)
//            .build()
//
//        retrofit = Retrofit.Builder()
//            .baseUrl(rpcBaseUrl.ensureTrailingSlash()) // 🔑 must end with /
//            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
//            .client(client)
//            .build()
//    }

    @OptIn(ExperimentalSerializationApi::class)
    fun <T> createService(rpcBaseUrl: String, service: Class<T>): T {
        val json = Json { ignoreUnknownKeys = true }
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)   // increase timeouts
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(rpcBaseUrl.ensureTrailingSlash()) // 🔑 must end with /
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .client(client)
            .build()

        if (!::retrofit.isInitialized) {
            throw IllegalStateException("RpcConfig must be initialized first")
        }
        return retrofit.create(service)
    }
}

// helper to guarantee trailing slash
private fun String.ensureTrailingSlash(): String =
    if (endsWith("/")) this else "$this/"
