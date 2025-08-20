package com.altude.core.data


import foundation.metaplex.mplbubblegum.generated.bubblegum.MetadataArgs
import foundation.metaplex.solanapublickeys.PublicKey


data class MintOption (
    val uri: String,
    val symbol: String,
    val name: String,
    val owner: String = "",
    val sellerFeeBasisPoints: Int,
    val collection: String,
)


