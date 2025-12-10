package com.altude.core.api

import com.altude.core.data.JsonRpc20Request
import com.altude.core.data.RpcResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST




interface RpcService {
    @POST(".")
    suspend fun callRpc(
        @Header("Authorization") bearerToken: String,
        @Body body: JsonRpc20Request<JsonElement> // fixed to JsonElement
    ): JsonElement
}

suspend inline fun <reified T> RpcService.callRpcTyped(
    json: Json,
    bearerToken: String,
    body: JsonRpc20Request<JsonElement>
): RpcResponse<T> {
    val jsonElement = callRpc(bearerToken, body)

    return json.decodeFromJsonElement<RpcResponse<T>>(jsonElement)
}