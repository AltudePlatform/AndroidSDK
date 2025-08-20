package com.altude.core.data

import com.altude.core.helper.AccountDataPolymorphicSerializer
import com.altude.core.helper.U64AsStringSerializer
import kotlinx.serialization.Serializable

@Serializable
data class AccountInfoResponse(
    val result: AccountInfoResult
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
    val owner: String? = null,
    @Serializable(with = U64AsStringSerializer::class)
    val rentEpoch: String? = null
)

@Serializable
open class AccountData {

    @Serializable
    data class Parsed(
        val parsed: AccountParsed? = null,
        val program: String? = null,
        val space: Int? = null
    ) : AccountData()

    @Serializable
    data class Raw(val bytes: ByteArray) : AccountData() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Raw

            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            return bytes.contentHashCode()
        }
    }
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
    val delegatedAmount: TokenAmount? = null,
    val state: String? = null,
    val isNative: Boolean? = null,
    val rentExemptReserve: TokenAmount? = null,
    val tokenAmount: TokenAmount? = null
)

@Serializable
data class TokenAmount(
    val amount: String,
    val decimals: Int,
    val uiAmount: Double? = null,
    val uiAmountString: String
)

