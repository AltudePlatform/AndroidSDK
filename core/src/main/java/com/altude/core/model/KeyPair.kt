package com.altude.core.model

import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters



object KeyPair {
    suspend fun generate(): Keypair{
        return SolanaEddsa.generateKeypair()
    }
//    fun deriveSolanaKeypairFromMnemonic(mnemonic: String, passphrase: String = ""): SolanaKeypair {
//        val words = mnemonic.trim().split(" ")
//        MnemonicCode.INSTANCE.check(words) // throws if invalid
//
//        val seed = MnemonicCode.toSeed(words, passphrase)
//
//        // SLIP-0010 master key for Ed25519 (first 32 bytes of seed)
//        val privateKeySeed = seed.copyOfRange(0, 32) // Take only first 32 bytes
//
//        // Generate Ed25519 keypair from seed
//        val privateKey = Ed25519PrivateKeyParameters(privateKeySeed, 0)
//        val publicKey = PublicKey(privateKey.generatePublicKey().encoded)
//
//        return SolanaKeypair(publicKey,privateKey.encoded, )
//    }
    suspend fun solanaKeyPairFromPrivateKey(PrivateKeyByteArray: ByteArray): SolanaKeypair{
        val keypair = SolanaEddsa.createKeypairFromSecretKey(PrivateKeyByteArray)
        return SolanaKeypair(keypair.publicKey,keypair.secretKey)
    }
}