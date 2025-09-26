package com.altude.gasstation.data

import com.altude.core.helper.Mnemonic
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa

object KeyPair {

    suspend fun generate(): Keypair {
        return SolanaEddsa.generateKeypair()
    }
    suspend fun solanaKeyPairFromPrivateKey(privateKeyByteArray: ByteArray): Keypair {
        val keypair = SolanaEddsa.createKeypairFromSecretKey(privateKeyByteArray.copyOfRange(0,32))
        return SolanaKeypair(keypair.publicKey, keypair.secretKey)
    }
    suspend fun solanaKeyPairFromMnemonic(mnemonic: String, passphrase: String = "") : Keypair {
        val seed = Mnemonic(mnemonic, passphrase).getKeyPair()
        return seed
    }

    // Helper extension function (can be top-level or in a utility class)
    //fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }



//    fun mymnemonic(){
//        Bip39PhraseUseCase.mnemonic =
//        Bip39
//
//    }

}