package com.altude.core.config

import com.altude.core.config.SdkConfig.apiKey
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object JupiterConfig {
    private lateinit var retrofit: Retrofit
    var rpcBaseUrl = "https://lite-api.jup.ag"

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
    fun <T> createService( service: Class<T>): T {
        val json = Json { ignoreUnknownKeys = true }
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(provideApiKeyInterceptor())
            .connectTimeout(60, TimeUnit.SECONDS)   // increase timeouts
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(rpcBaseUrl.ensureTrailingSlash()) // 🔑 must end with /
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .client(client)
            .build()

        
        
        
        return retrofit.create(service)
    }
    private fun provideApiKeyInterceptor(): Interceptor {
        return Interceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()

            apiKey.let {
                builder.addHeader("X-API-Key", it)
            }

            val request = builder.build()
            chain.proceed(request)
        }
    }
}
fun String.ensureTrailingSlash(): String =
    if (endsWith("/")) this else "$this/"