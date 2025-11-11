package com.altude.core.api

import com.altude.core.data.SwapInstructionRequest
import com.altude.core.data.SwapRequest
import kotlinx.serialization.json.JsonElement
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface JupiterService {
    @POST("quote")
    fun quote(
        @Body body: SwapRequest
    ): Call<JsonElement>
    @POST("swap-instructions")
    fun swap(
        @Body body: SwapInstructionRequest
    ): Call<JsonElement>
}