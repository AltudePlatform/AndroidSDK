package com.altude.nft.data

import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanapublickeys.PublicKey

class SolanaKeypair (
    override val publicKey: PublicKey,
    override val secretKey: ByteArray
) : Keypair