package com.altude.core.config

import android.annotation.SuppressLint
import android.content.Context
import com.altude.core.api.ConfigResponse
import com.altude.core.api.TransactionService
import com.altude.core.service.StorageService
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.solana.transaction.MessageSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
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
import kotlin.time.ExperimentalTime

object SdkConfig {


    private var baseUrl: String =  "https://api.altude.so" //"http://10.0.2.2:54363/"//
    var apiKey: String = ""
    //lateinit var ownerKeyPair: Keypair
    var isDevnet: Boolean = false

    @OptIn(ExperimentalTime::class)
    var apiConfig = ConfigResponse()
    private lateinit var retrofit: Retrofit
    private lateinit var okHttpClient: OkHttpClient

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun initialize() {
        this.baseUrl;

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val trustAllCerts: Array<TrustManager> = arrayOf<TrustManager>(
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
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
                (trustAllCerts[0] as X509TrustManager?)!!
            )
            .hostnameVerifier(HostnameVerifier { hostname: String?, session: SSLSession? -> true })


        okHttpClient = client
            .addInterceptor(provideApiKeyInterceptor())
            .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS)   // increase timeouts
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        val json = Json { ignoreUnknownKeys = true }
        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            //.addConverterFactory(GsonConverterFactory.create())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .client(okHttpClient)
            .build()
        val service = retrofit.create(TransactionService::class.java)
        this.apiConfig = service.getConfig().await()
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
    suspend fun setApiKey(context: Context, key: String) {
        this.apiKey = key
        this.initialize()
        StorageService.init(context)
    }

    fun setNetwork(isDevnet: Boolean){
        this.isDevnet = isDevnet
    }
    
    /**
     * Validates that the API configuration is compatible with the requested network.
     * Helps prevent Address Lookup Table errors by detecting cluster mismatches.
     */
    fun validateNetworkConfiguration(): String? {
        val rpcUrl = apiConfig.RpcUrl.lowercase()
        val environment = apiConfig.RpcEnvironment.lowercase()
        
        if (isDevnet) {
            // Check if devnet is configured but API points to mainnet
            if (!rpcUrl.contains("devnet") && (rpcUrl.contains("mainnet") || environment.contains("mainnet"))) {
                return "ALT Error Risk: API key configured for $environment but SDK set to DevNet. " +
                       "This will cause Address Lookup Table errors because mainnet ALTs don't exist on DevNet. " +
                       "Please use a DevNet-configured API key."
            }
        } else {
            // Check if mainnet is configured but API points to devnet
            if (rpcUrl.contains("devnet") || environment.contains("devnet")) {
                return "Network Mismatch: API key configured for DevNet but SDK set to mainnet. " +
                       "Consider using setNetwork(isDevnet = true) or get a mainnet API key."
            }
        }
        
        return null // Configuration is valid
    }
    
    /**
     * Gets the expected cluster name based on current configuration
     */
    fun getExpectedCluster(): String {
        return if (isDevnet) "devnet" else "mainnet"
    }
    
    /**
     * Gets the actual cluster from API configuration
     */
    fun getActualCluster(): String {
        val rpcUrl = apiConfig.RpcUrl.lowercase()
        val environment = apiConfig.RpcEnvironment.lowercase()
        
        return when {
            rpcUrl.contains("devnet") || environment.contains("devnet") -> "devnet"
            rpcUrl.contains("mainnet") || environment.contains("mainnet") -> "mainnet"
            else -> "unknown ($environment)"
        }
    }
    
    //fun setConfig()


    fun <T> createService(service: Class<T>): T {
        if (!::retrofit.isInitialized) {
            throw IllegalStateException("SdkConfig must be initialized before creating services")
        }
        return retrofit.create(service)
    }
}