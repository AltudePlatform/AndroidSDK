// core/config/SdkConfig.kt

/* Usage Example in SDK Entry Point or App

SdkConfig.initialize("https://api.yourdomain.com/")
SdkConfig.setApiKey("your-api-key")

val service = SdkConfig.createService(TransactionService::class.java)
*/

package com.altude.core.config

import android.annotation.SuppressLint
import com.altude.core.model.KeyPair
import foundation.metaplex.solanaeddsa.Keypair
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


object SdkConfig {


    private var baseUrl: String = "http://10.0.2.2:5250"
    private var apiKey: String = ""
    lateinit var ownerKeyPair: Keypair
    var isDevnet: Boolean = true
    private lateinit var retrofit: Retrofit
    private lateinit var okHttpClient: OkHttpClient

    fun initialize() {
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
        this.initialize()
    }

    fun setNetwork(isDevnet: Boolean){
        this.isDevnet = isDevnet
    }

    //fun setConfig()
    suspend fun setNemonic(key: String) {
        ownerKeyPair = KeyPair.generate()
    }
    suspend fun setPrivateKey(byteArraySecretKey: ByteArray ) {
        ownerKeyPair = KeyPair.solanaKeyPairFromPrivateKey(byteArraySecretKey.copyOfRange(0,32))
    }

    fun <T> createService(service: Class<T>): T {
        if (!::retrofit.isInitialized) {
            throw IllegalStateException("SdkConfig must be initialized before creating services")
        }
        return retrofit.create(service)
    }
}
