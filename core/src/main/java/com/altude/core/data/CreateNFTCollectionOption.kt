package com.altude.core.data

data class CreateNFTCollectionOption(
    val account: String = "",
    val metadataUri: String,
    val name: String,
    val sellerFeeBasisPoints: Int
)
