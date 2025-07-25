package com.altude.core.model
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
data class AccountInfo(
    @SerialName("rentEpoch")
    val rentEpoch: String // or BigInteger (if you handle it properly)
)
@Serializable
data class MintLayout(
    @SerialName("mintAuthority")
    val mintAuthority: ByteArray? = null,

    @SerialName("supply")
    val supply: Long = 0,

    @SerialName("decimals")
    val decimals: Int = 0,

    @SerialName("isInitialized")
    val isInitialized: Boolean = false,

    @SerialName("freezeAuthority")
    val freezeAuthority: ByteArray? = null

) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MintLayout

        if (supply != other.supply) return false
        if (decimals != other.decimals) return false
        if (isInitialized != other.isInitialized) return false
        if (!mintAuthority.contentEquals(other.mintAuthority)) return false
        if (!freezeAuthority.contentEquals(other.freezeAuthority)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = supply.hashCode()
        result = 31 * result + decimals
        result = 31 * result + isInitialized.hashCode()
        result = 31 * result + (mintAuthority?.contentHashCode() ?: 0)
        result = 31 * result + (freezeAuthority?.contentHashCode() ?: 0)
        return result
    }
}

