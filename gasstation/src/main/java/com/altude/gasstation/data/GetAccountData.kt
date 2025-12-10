package com.altude.gasstation.data
import kotlinx.serialization.Serializable

@Serializable
data class GetBalanceResponse(
    val TokenInfos: List<TokenInfo?>,
    val AccountAddress: String? = null,
    val Balance: Double? = null,
    val Symbol: String? = null
)

@Serializable
data class TokenInfo(
    val Account: Account? = null,
    val pubkey: String? = null
)

@Serializable
data class Account(
    val Data: Data? = null,
    val Lamports: Long? = null,
    val Owner: String? = null,
    val Executable: Boolean? = null,
    val RentEpoch: String? = null
)

@Serializable
data class Parsed(
    val Type: String? = null,
    val Info: ParsedInfo? = null
)

@Serializable
data class ParsedInfo(
    val TokenAmount: TokenAmount,
    val Delegate: String? = null,
    val DelegatedAmount: String? = null,
    val State: String? = null,
    val IsNative: Boolean? = null,
    val Mint: String? = null,
    val Owner: String? = null
)

@Serializable
data class TokenAmount(
    val Amount: Int? = null,
    val Decimals: Int? = null,
    val UiAmount: Double? = null,
    val UiAmountString: String? = null,
    val AmountUlong: Long? = null,
    val AmountDecimal: Double? = null,
    val AmountDouble: Double? = null
)

@Serializable
data class GetAccountResponse(
    val AccountAddress: String,
    val AccountInfo: AccountInfo,
    val TokenInfos: List<TokenInfo>
)

@Serializable
data class Data(
    val Program: String,
    val Space: Int,
    val Parsed: Parsed
)
@Serializable
data class AccountInfo(
    val Lamports: Long? = null,
    val Owner: String? = null,
    val Executable: Boolean? = null,
    val RentEpoch: String? = null
)