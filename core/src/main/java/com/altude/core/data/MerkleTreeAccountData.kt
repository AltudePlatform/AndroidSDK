package com.altude.core.data

import foundation.metaplex.mplbubblegum.generated.bubblegum.hook.ConcurrentMerkleTree
import foundation.metaplex.solanapublickeys.PublicKey

data class MerkleTreeAccountData(
    val discriminator: UByte, // u8
    val treeHeader: ConcurrentMerkleTreeHeaderData,
    val tree: ConcurrentMerkleTree,
    val canopy: List<PublicKey>
)
