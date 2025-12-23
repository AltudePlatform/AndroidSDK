package com.altude.gasstation.data

import com.altude.gasstation.data.AccountDataPolymorphicSerializer
import com.altude.core.helper.U64AsStringSerializer
import kotlinx.serialization.Serializable

@Serializable
data class AccountInfoResponse(
    val result: AccountInfoResult? = null
)

@Serializable
data class AccountInfoResult(
    val value: AccountInfoValue? = null
)

@Serializable
data class AccountInfoValue(
    @Serializable(with = AccountDataPolymorphicSerializer::class)
    val data: AccountData? = null,
    val executable: Boolean? = null,
    val lamports: Long? = null,
    val space: Long? = null,
    val owner: String? = null,
    @Serializable(with = U64AsStringSerializer::class)
    val rentEpoch: String? = null
)

@Serializable
class AccountData {
    val parsed: AccountParsed? = null
    val program: String? = null
    val space: Int? = null
}

@Serializable
data class AccountParsed(
    val type: String? = null,
    val info: AccountParsedInfo? = null
)

@Serializable
data class AccountParsedInfo(
    // Mint account fields
    val decimals: Int? = null,
    val mintAuthority: String? = null,
    val supply: String? = null,
    val freezeAuthority: String? = null,
    val isInitialized: Boolean? = null,

    // Token account fields
    val owner: String? = null,
    val closeAuthority: String? = null,
    val delegate: String? = null,
    val delegatedAmount: RpcTokenAmount? = null,
    val state: String? = null,
    val isNative: Boolean? = null,
    val rentExemptReserve: RpcTokenAmount? = null,
    val tokenAmount: RpcTokenAmount? = null,
    val mint: String? = null
)

@Serializable
data class RpcTokenAmount(
    val amount: Long? = null,
    val decimals: Int? = null,
    val uiAmount: Double? = null,
    val uiAmountString: String? = null,
    val amountUlong: Long? = null,
    val amountDecimal: Double? = null,
    val amountDouble: Double? = null
)

