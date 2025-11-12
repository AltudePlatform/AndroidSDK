package com.altude.core.api

import com.altude.core.data.SwapInstructionRequest
import com.altude.core.data.SwapRequest
import kotlinx.serialization.json.JsonElement
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface JupiterService {
    @GET("swap/v1/quote")
    fun quote(
        @QueryMap params: Map<String, @JvmSuppressWildcards Any>
    ): Call<JsonElement>
    @POST("swap/v1/swap-instructions")
    fun swap(
        @Body body: SwapInstructionRequest
    ): Call<JsonElement>
    @GET("ultra/v1/shield")
    fun shield(
        @Query("mints") token : String
    ): Call<JsonElement>
    @GET("ultra/v1/search")
    fun search(
        @Query("query") token : String
    ): Call<JsonElement>
}