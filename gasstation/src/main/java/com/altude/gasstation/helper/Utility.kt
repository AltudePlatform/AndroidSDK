package com.altude.gasstation.helper

import com.altude.core.config.SdkConfig
import com.altude.gasstation.data.AccountInfoResult
import com.altude.gasstation.data.AccountInfoValue
import com.altude.core.network.QuickNodeRpc


object  Utility {
    val QUICKNODE_URL = SdkConfig.apiConfig.rpcUrl//"https://cold-holy-dinghy.solana-devnet.quiknode.pro/8cc52fd5faf72bedbc72d9448fba5640cd728ace/"//"https://multi-ultra-frost.solana-devnet.quiknode.pro/417151c175bae42230bf09c1f87acda90dc21968/" //change this with envi variable
    suspend fun getAccountInfo(publicKey: String, useBase64: Boolean = false): AccountInfoValue? {
        val rpc = QuickNodeRpc(QUICKNODE_URL)
        return  rpc.getAccountInfo<AccountInfoResult>(publicKey).value
    }
    suspend fun ataExists(publicKey: String = ""): Boolean {
        val response = getAccountInfo(publicKey)
        return response != null
    }
    suspend fun validateAta(publicKey: String, expectedOwner: String) {
        val response = getAccountInfo(publicKey)
        val value = response
            ?: throw Error("Associated token account does not exist!")

        val actualOwner = value.data?.parsed?.info?.owner
        if (expectedOwner != actualOwner) {
            throw Error("Authorized owner is $actualOwner, expected $expectedOwner")
        }
    }
    suspend fun getTokenDecimals(mintAddress: String): Int {
        val response = getAccountInfo(mintAddress)

        val decimals = response?.data?.parsed?.info?.decimals

        return decimals ?: throw Exception("Unable to parse token decimals from mint: $mintAddress")
    }
}