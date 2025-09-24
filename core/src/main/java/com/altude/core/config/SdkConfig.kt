// core/config/SdkConfig.kt

/* Usage Example in SDK Entry Point or App

SdkConfig.initialize("https://api.yourdomain.com/")
SdkConfig.setApiKey("your-api-key")

val service = SdkConfig.createService(TransactionService::class.java)
*/

package com.altude.core.config

import android.annotation.SuppressLint
import android.content.Context
import com.altude.core.api.ConfigResponse
import com.altude.core.api.TransactionService
import com.altude.core.service.StorageService
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.await
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


object SdkConfig {


    private var baseUrl: String = "https://api.altude.so" //"http://10.0.2.2:5000" //
    private var apiKey: String = ""
    //lateinit var ownerKeyPair: Keypair
    var isDevnet: Boolean = true

    var apiConfig = ConfigResponse()
    private lateinit var retrofit: Retrofit
    private lateinit var okHttpClient: OkHttpClient

    suspend fun initialize() {
        this.baseUrl;

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val trustAllCerts: Array<TrustManager> = arrayOf<TrustManager>(object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<X509Certificate?>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate?>? {
                return arrayOfNulls<X509Certificate>(0)
            }
        }
        )

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val sslSocketFactory: SSLSocketFactory = sslContext.getSocketFactory()

        val client = OkHttpClient.Builder()

            .sslSocketFactory(
                sslSocketFactory,
                (trustAllCerts[0] as javax.net.ssl.X509TrustManager?)!!
            )
            .hostnameVerifier(HostnameVerifier { hostname: String?, session: SSLSession? -> true })


        okHttpClient = client
            .addInterceptor(provideApiKeyInterceptor())
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)   // increase timeouts
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()

        val service = retrofit.create(TransactionService::class.java)
        apiConfig = service.getConfig().await()
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
    suspend fun setApiKey(context: Context, key: String) {
        this.apiKey = key
        this.initialize()
        StorageService.init(context)
    }

    fun setNetwork(isDevnet: Boolean){
        this.isDevnet = isDevnet
    }

    //fun setConfig()


    fun <T> createService(service: Class<T>): T {
        if (!::retrofit.isInitialized) {
            throw IllegalStateException("SdkConfig must be initialized before creating services")
        }
        return retrofit.create(service)
    }
}
