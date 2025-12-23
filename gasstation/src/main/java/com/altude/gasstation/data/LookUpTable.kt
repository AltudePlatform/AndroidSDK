package com.altude.gasstation.data
import com.altude.core.helper.U64AsStringSerializer
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64

@Serializable
data class LookUpTableResponse(
    val result: LookUpTableResult
)

@Serializable
data class LookUpTableResult(
    val value: LookUpTableValue? = null
)

@Serializable
data class LookUpTableValue(
    val data: List<String>? = null,
    val executable: Boolean? = null,
    val lamports: Long? = null,
    val space: Long? = null,
    val owner: String? = null,
    @Serializable(with = U64AsStringSerializer::class)
    val rentEpoch: String? = null
)

@Serializable
class LookUpTableData {
    val parsed: LookUpTableParsed? = null
    val program: String? = null
    val space: Int? = null
}

@Serializable
data class LookUpTableParsed(
    val type: String? = null,
    val info: LookUpTableParsedInfo? = null
)

@Serializable
data class LookUpTableParsedInfo(
    // Mint account fields
    val addresses: List<String>? = null
)

data class AddressLookupTable(
    val authority: PublicKey,
    val lastExtendedSlot: Long,
    val addresses: List<PublicKey>
)

fun parseLookupTableAccountBase64(dataBase64: String): AddressLookupTable {
    val data = Base64.decode(dataBase64)
    val buffer = ByteBuffer.wrap(data)

    // Skip state byte (1) + deactivateSlot (8)
    buffer.position(9)

    // Read lastExtendedSlot (8 bytes, little endian)
    val lastExtendedSlot = java.lang.Long.reverseBytes(buffer.long)

    // Read authority (32 bytes)
    val authorityBytes = ByteArray(32)
    buffer.get(authorityBytes)
    val authority = PublicKey(authorityBytes)

    // Skip padding (7 bytes)
    buffer.position(buffer.position() + 7)

    // Read remaining addresses (32 bytes each)
    val addresses = mutableListOf<PublicKey>()
    while (buffer.remaining() >= 32) {
        val addrBytes = ByteArray(32)
        buffer.get(addrBytes)
        addresses.add(PublicKey(addrBytes))
    }

    return AddressLookupTable(authority, lastExtendedSlot, addresses)
}