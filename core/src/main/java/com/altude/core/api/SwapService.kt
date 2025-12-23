package com.altude.core.api

import com.altude.core.data.SwapInstructionRequest
import kotlinx.serialization.json.JsonElement
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.QueryMap

interface SwapService {
    @GET("quote")
    fun quote(
        @QueryMap params: Map<String, @JvmSuppressWildcards Any>
    ): Call<JsonElement>
    @POST("swap-instructions")
    fun swapInstruction(
        @Body body: SwapInstructionRequest
    ): Call<JsonElement>
    @POST("swap")
    fun swap(
        @Body body: SwapInstructionRequest
    ): Call<JsonElement>
}