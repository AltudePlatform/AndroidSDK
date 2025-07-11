package com.altude.core.crypto

import android.os.Build
import androidx.annotation.RequiresApi
import java.security.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.spec.PKCS8EncodedKeySpec
import android.util.Base64
import java.security.spec.X509EncodedKeySpec

object KeyUtils {

    init {
        Security.addProvider(BouncyCastleProvider()) // Needed for Ed25519
    }

    fun loadEd25519PrivateKeyFromBase64(base64: String): PrivateKey {

        val keyBytes = Base64.decode(base64, Base64.NO_WRAP)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("Ed25519", "BC").generatePrivate(spec)
    }

    fun loadEd25519PublicKeyFromBase64(base64: String): PublicKey {
        val keyBytes = Base64.decode(base64, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("Ed25519", "BC").generatePublic(spec)
    }

    fun generateEd25519KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519", "BC")
        return kpg.generateKeyPair()
    }
    fun byteArrayToBase64(byteArray: ByteArray): String {
        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        return base64String
    }
}
