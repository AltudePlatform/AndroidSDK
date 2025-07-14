// core/config/SdkConfig.kt

/* Usage Example in SDK Entry Point or App

SdkConfig.initialize("https://api.yourdomain.com/")
SdkConfig.setApiKey("your-api-key")

val service = SdkConfig.createService(TransactionService::class.java)
*/

package com.altude.core.config

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object SdkConfig {

    private var baseUrl: String = "https://localhost:7021"
    private var apiKey: String = ""

    private lateinit var retrofit: Retrofit
    private lateinit var okHttpClient: OkHttpClient

    fun initialize(baseUrl: String) {
        this.baseUrl = baseUrl

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }



        okHttpClient = OkHttpClient.Builder()
            .addInterceptor(provideApiKeyInterceptor())
            .addInterceptor(logging)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
    }

    private fun provideApiKeyInterceptor(): Interceptor {
        return Interceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()

            apiKey.let {
                builder.addHeader("x-api-key", it)
            }

            val request = builder.build()
            chain.proceed(request)
        }
    }
    fun setApiKey(key: String) {
        this.apiKey = key
        this.initialize("")
    }

    fun <T> createService(service: Class<T>): T {
        if (!::retrofit.isInitialized) {
            throw IllegalStateException("SdkConfig must be initialized before creating services")
        }
        return retrofit.create(service)
    }
}
