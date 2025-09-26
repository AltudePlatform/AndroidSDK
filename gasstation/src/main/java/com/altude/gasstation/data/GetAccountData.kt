package com.altude.gasstation.data
import kotlinx.serialization.Serializable

@Serializable
data class GetBalanceResponse(
    val tokenInfos: List<TokenInfo?>,
    val accountAddress: String? = null,
    val balance: Double? = null,
    val symbol: String? = null
)

@Serializable
data class TokenInfo(
    val account: Account? = null,
    val pubkey: String? = null
)

@Serializable
data class Account(
    val data: Data? = null,
    val lamports: Long? = null,
    val owner: String? = null,
    val executable: Boolean? = null,
    val rentEpoch: String? = null
)

@Serializable
data class Parsed(
    val type: String? = null,
    val info: ParsedInfo? = null
)

@Serializable
data class ParsedInfo(
    val tokenAmount: TokenAmount,
    val delegate: String? = null,
    val delegatedAmount: String? = null,
    val state: String? = null,
    val isNative: Boolean? = null,
    val mint: String? = null,
    val owner: String? = null
)

@Serializable
data class TokenAmount(
    val amount: String? = null,
    val decimals: Int? = null,
    val uiAmount: Double? = null,
    val uiAmountString: String? = null,
    val amountUlong: Long? = null,
    val amountDecimal: Double? = null,
    val amountDouble: Double? = null
)

@Serializable
data class GetAccountResponse(
    val accountAddress: String,
    val accountInfo: AccountInfo,
    val tokenInfos: List<TokenInfo>
)

@Serializable
data class Data(
    val program: String,
    val space: Int,
    val parsed: Parsed
)
@Serializable
data class AccountInfo(
    val lamports: Long? = null,
    val owner: String? = null,
    val executable: Boolean? = null,
    val rentEpoch: String? = null
)