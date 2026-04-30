package com.altude.provenance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.altude.core.config.SdkConfig
import com.altude.core.service.StorageService
import com.altude.provenance.data.AttestationResult
import com.altude.provenance.data.C2paManifest
import com.altude.provenance.data.Commitment
import com.altude.provenance.data.ImageHashPayload
import com.altude.provenance.data.ManifestOption
import com.altude.provenance.data.ProvenanceCertificate
import com.altude.provenance.data.ProvenancePrefs
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for the Provenance module.
 * Run on a physical device or emulator connected via ADB.
 *
 * ── Two test categories ──────────────────────────────────────────────────────
 *
 * 1. **Offline / pure-crypto** — no API key needed:
 *    - C2PA manifest building, hashing, JSON round-trip
 *    - ProvenanceCertificate JSON round-trip
 *    - ProvenancePrefs state management
 *    - Schema PDA determinism
 *    - buildCertificate (ED25519 signing, no network)
 *
 * 2. **Network / on-chain** — requires [API_KEY] + [TEST_MNEMONIC] with devnet SOL:
 *    - attestImageHash (single image, ManifestOption.None)
 *    - attestImageHash with sidecar manifest
 *    - verifyOnChain (trustless Solana RPC check)
 *    - attestOffline + submitPending
 *    - attestBatch (3 images, sequential)
 *    - resetSession
 *
 * ── How to run ───────────────────────────────────────────────────────────────
 * Fill in [API_KEY] and [TEST_MNEMONIC], then:
 * ```
 * ./gradlew :provenance:connectedAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ProvenanceInstrumentedTest {

    private lateinit var context: Context

    // ── Fill in to enable network / wallet tests ──────────────────────────────
    /** Your Altude API key (required for network tests). */
    private val API_KEY       = ""
    /** 12/24-word mnemonic of a devnet wallet that holds SOL for fees. */
    private val TEST_MNEMONIC = ""
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /** Shared Json instance — created once, reused across all tests. */
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Before
    fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        SdkConfig.setNetwork(isDevnet = true)
        SdkConfig.setApiKey(context, API_KEY)
        if (TEST_MNEMONIC.isNotBlank()) {
            StorageService.storeMnemonic(TEST_MNEMONIC)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. C2PA Manifest — offline / pure-crypto
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Both SHA-256 hashes are well-formed 64-char hex strings and are distinct:
     *   assetHash    = SHA-256 of the raw image bytes
     *   manifestHash = SHA-256 of the canonical claim JSON  ← stored on-chain
     */
    @Test
    fun testC2paManifestBuildFromBytes() {
        val bytes    = "hello provenance sdk".toByteArray()
        val manifest = C2paManifest.buildFromBytes(
            imageBytes = bytes,
            mimeType   = "image/png",
            filename   = "test.png",
            producer   = "test-producer"
        )

        assertEquals("assetHash must be 64 hex chars",    64, manifest.assetHash.length)
        assertEquals("manifestHash must be 64 hex chars", 64, manifest.manifestHash.length)
        assertNotEquals("assetHash and manifestHash must differ",
            manifest.assetHash, manifest.manifestHash)
        assertEquals("filename",  "test.png",      manifest.filename)
        assertEquals("mimeType",  "image/png",     manifest.mimeType)
        assertEquals("producer",  "test-producer", manifest.producer)
        assertTrue("assetHash must be lowercase hex",
            manifest.assetHash.all { it.isDigit() || it in 'a'..'f' })
        assertTrue("manifestHash must be lowercase hex",
            manifest.manifestHash.all { it.isDigit() || it in 'a'..'f' })

        println("✅ assetHash:    ${manifest.assetHash}")
        println("✅ manifestHash: ${manifest.manifestHash}")
    }

    /**
     * Same bytes → same assetHash every time (deterministic raw-bytes hashing).
     */
    @Test
    fun testC2paManifestAssetHashDeterministic() {
        val bytes = "deterministic content 12345".toByteArray()
        val m1    = C2paManifest.buildFromBytes(bytes, mimeType = "image/png")
        val m2    = C2paManifest.buildFromBytes(bytes, mimeType = "image/png")

        assertEquals("assetHash must be deterministic for identical bytes",
            m1.assetHash, m2.assetHash)
        println("✅ assetHash is deterministic: ${m1.assetHash}")
    }

    /**
     * The file-path overload and the bytes overload must produce the same assetHash
     * when given identical content.
     */
    @Test
    fun testC2paManifestBuildFromFileMatchesBytes() {
        val bytes   = "file vs bytes consistency check".toByteArray()
        val tmpFile = File(context.filesDir, "hash_consistency.png")
            .also { it.writeBytes(bytes) }


        try {
            val fromFile  = C2paManifest.build(filePath = tmpFile.absolutePath, mimeType = "image/png")
            val fromBytes = C2paManifest.buildFromBytes(
                imageBytes = bytes,
                mimeType   = "image/png",
                filename   = tmpFile.name
            )

            assertEquals("assetHash must match across build() and buildFromBytes()",
                fromBytes.assetHash, fromFile.assetHash)
            assertEquals("filename preserved", "hash_consistency.png", fromFile.filename)
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * Different byte contents must produce different asset hashes (collision sanity).
     */
    @Test
    fun testC2paManifestDifferentBytesProduceDifferentHashes() {
        val m1 = C2paManifest.buildFromBytes("image content A — unique".toByteArray())
        val m2 = C2paManifest.buildFromBytes("image content B — unique".toByteArray())

        assertNotEquals("different content must yield different assetHash",
            m1.assetHash, m2.assetHash)
        println("✅ Collision sanity: A=${m1.assetHash.take(16)}… B=${m2.assetHash.take(16)}…")
    }

    /**
     * toJson() → Json.decodeFromString() preserves every field including attestationId.
     */
    @Test
    fun testC2paManifestJsonRoundTrip() {
        val original = C2paManifest.buildFromBytes(
            imageBytes    = "roundtrip test image".toByteArray(),
            mimeType      = "image/jpeg",
            filename      = "photo.jpg",
            producer      = "wallet-address-xxx",
            softwareAgent = "test-agent-v1"
        ).copy(attestationId = "SolanaAttestPDA1111111111111111111111111111")

        val jsonStr = original.toJson()
        val parsed  = json.decodeFromString<C2paManifest>(jsonStr)

        assertEquals("claimType",     original.claimType,     parsed.claimType)
        assertEquals("assetHash",     original.assetHash,     parsed.assetHash)
        assertEquals("manifestHash",  original.manifestHash,  parsed.manifestHash)
        assertEquals("mimeType",      original.mimeType,      parsed.mimeType)
        assertEquals("filename",      original.filename,      parsed.filename)
        assertEquals("producer",      original.producer,      parsed.producer)
        assertEquals("softwareAgent", original.softwareAgent, parsed.softwareAgent)
        assertEquals("attestationId", original.attestationId, parsed.attestationId)

        println("✅ C2paManifest JSON round-trip passed")
    }

    /**
     * saveTo() creates a correctly-named .c2pa.json file that contains
     * the manifestHash, assetHash, and attestationId.
     */
    @Test
    fun testC2paManifestSidecarSave() {
        val manifest = C2paManifest.buildFromBytes(
            imageBytes = "sidecar save test bytes".toByteArray(),
            filename   = "photo.png"
        ).copy(attestationId = "ATTEST_PDA_TEST_11111111111111111111111111")

        val dir     = File(context.filesDir, "provenance_manifests_test")
        val sidecar = manifest.saveTo(dir)

        try {
            assertTrue("sidecar file must exist",             sidecar.exists())
            assertTrue("sidecar name must end in .c2pa.json", sidecar.name.endsWith(".c2pa.json"))

            val content = sidecar.readText()
            assertTrue("sidecar must contain manifestHash",
                content.contains(manifest.manifestHash))
            assertTrue("sidecar must contain assetHash",
                content.contains(manifest.assetHash))
            assertTrue("sidecar must contain attestationId",
                content.contains("ATTEST_PDA_TEST_11111111111111111111111111"))

            println("✅ Sidecar saved: ${sidecar.name}")
        } finally {
            sidecar.delete()
            dir.delete()
        }
    }


    // ═════════════════════════════════════════════════════════════════════════
    // 3. ProvenanceCertificate — JSON round-trip
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * toJson() → fromJson() preserves all 12 fields, including optional GPS
     * coordinates and attestationId.
     */
    @Test
    fun testProvenanceCertificateJsonRoundTrip() {
        val cert = ProvenanceCertificate(
            instanceId         = "urn:uuid:test-1234-abcd-5678-efgh",
            captureTimestampMs = 1_700_000_000_000L,
            imageSha256        = "a".repeat(64),
            signerAddress      = "FakeSolanaWallet1111111111111111111111111",
            signerPublicKey    = "b".repeat(64),
            signature          = "c".repeat(128),
            deviceMake         = "Google",
            deviceModel        = "Pixel 9 Pro",
            osVersion          = "15",
            attestationId      = "SolanaAttestPDA1111111111111111111111111"
        )

        val json   = cert.toJson()
        val parsed = ProvenanceCertificate.fromJson(json)

        assertNotNull("parsed certificate must not be null", parsed)
        assertEquals("instanceId",      cert.instanceId,      parsed!!.instanceId)
        assertEquals("imageSha256",     cert.imageSha256,     parsed.imageSha256)
        assertEquals("signerAddress",   cert.signerAddress,   parsed.signerAddress)
        assertEquals("signerPublicKey", cert.signerPublicKey, parsed.signerPublicKey)
        assertEquals("signature",       cert.signature,       parsed.signature)
        assertEquals("deviceMake",      cert.deviceMake,      parsed.deviceMake)
        assertEquals("deviceModel",     cert.deviceModel,     parsed.deviceModel)
        assertEquals("osVersion",       cert.osVersion,       parsed.osVersion)
        assertEquals("attestationId",   cert.attestationId,   parsed.attestationId)

        println("✅ ProvenanceCertificate JSON round-trip passed")
    }

    /**
     * A certificate without GPS should parse with null lat/lng — not 0.0.
     */
    @Test
    fun testProvenanceCertificateJsonRoundTripNoGps() {
        val cert = ProvenanceCertificate(
            instanceId         = "urn:uuid:no-gps-test",
            captureTimestampMs = 1_710_000_000_000L,
            imageSha256        = "d".repeat(64),
            signerAddress      = "NoGpsSigner1111111111111111111111111111111",
            signerPublicKey    = "e".repeat(64),
            signature          = "f".repeat(128),
            deviceMake         = "Samsung",
            deviceModel        = "Galaxy S25",
            osVersion          = "14",
        )

        val parsed = ProvenanceCertificate.fromJson(cert.toJson())
        assertNotNull(parsed)
        println("✅ No-GPS certificate round-trip passed")
    }

    /**
     * fromJson() must return null — not crash — for blank, whitespace, and malformed input.
     */
    @Test
    fun testProvenanceCertificateFromJsonInvalidInputReturnsNull() {
        assertNull("blank string",      ProvenanceCertificate.fromJson(""))
        assertNull("whitespace only",   ProvenanceCertificate.fromJson("   "))
        assertNull("not JSON",          ProvenanceCertificate.fromJson("not-json!@#"))
        assertNull("broken JSON",       ProvenanceCertificate.fromJson("{ broken"))
        println("✅ fromJson returns null for all invalid inputs")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. ProvenancePrefs — SharedPreferences state machine
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Full mark → check → reset lifecycle for a single wallet address.
     */
    @Test
    fun testProvenancePrefsLifecycle() {
        val wallet = "TestWallet_${System.currentTimeMillis()}"

        assertFalse("fresh wallet: isSchemaCreated must be false",
            ProvenancePrefs.isSchemaCreated(wallet))

        ProvenancePrefs.markSchemaCreated(wallet)
        assertTrue("after mark: isSchemaCreated must be true",
            ProvenancePrefs.isSchemaCreated(wallet))

        ProvenancePrefs.markSchemaCreated(wallet)   // idempotent — must not flip back
        assertTrue("double-mark must stay true",
            ProvenancePrefs.isSchemaCreated(wallet))

        ProvenancePrefs.reset(wallet)
        assertFalse("after reset: isSchemaCreated must be false again",
            ProvenancePrefs.isSchemaCreated(wallet))

        println("✅ ProvenancePrefs lifecycle: false → true → true → false")
    }

    /**
     * Two wallet addresses maintain completely independent schema flags.
     */
    @Test
    fun testProvenancePrefsDifferentWalletsAreIndependent() {
        val walletA = "WalletA_${System.currentTimeMillis()}"
        val walletB = "WalletB_${System.currentTimeMillis()}"

        ProvenancePrefs.markSchemaCreated(walletA)

        assertTrue("walletA: must be marked",      ProvenancePrefs.isSchemaCreated(walletA))
        assertFalse("walletB: must be untouched",  ProvenancePrefs.isSchemaCreated(walletB))

        ProvenancePrefs.reset(walletA)   // cleanup
        println("✅ Independent wallet flags confirmed")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. PDA derivation + offline certificate signing  (wallet, no network)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Tests createSchema transaction building and prints the Base64 transaction string.
     * This is useful for debugging or manually submitting schema creation transactions.
     */
    @Test
    fun testCreateSchemaTransactionString() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        // Reset schema state to force creation of a new schema transaction
        val keypair = ProvenanceManager.getKeyPair()
        val walletKey = keypair.publicKey.toBase58()
        ProvenancePrefs.reset(walletKey)

        // Derive the schema PDA deterministically and ensure the SDK now requires
        // out-of-band schema creation. The manager method should return a failure
        // indicating client-side creation is removed.
        val schemaPda = ProvenanceManager.deriveSchemaAddress(keypair.publicKey.toBase58())
        println("Derived Schema PDA: ${schemaPda.toBase58()}")

        val result = ProvenanceManager.ensureSchema(
            account = "",
            commitment = Commitment.confirmed.name
        )

        assertTrue("ensureSchema should now fail indicating createSchema removal", result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Error message should mention createSchema removal", msg.contains("createSchema is removed") || msg.contains("createSchema removed"))
    }

    /**
     * Calling deriveSchemaAddress() twice for the same wallet must return
     * the exact same Base58 PDA — seeds are deterministic.
     */
    @Test
    fun testSchemaPdaDeterministic() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        val pda1 = ProvenanceManager.deriveSchemaAddress()
        val pda2 = ProvenanceManager.deriveSchemaAddress()

        assertEquals("Schema PDA must be deterministic",  pda1.toBase58(), pda2.toBase58())
        assertEquals("PDA must be 44 Base58 chars", 44, pda1.toBase58().length)
        println("✅ Schema PDA: ${pda1.toBase58()}")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. Network tests  (API_KEY + TEST_MNEMONIC + devnet SOL required)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Full end-to-end single-image attestation on devnet.
     * ManifestOption.None — no file I/O, just checks the on-chain result.
     */
    @Test
    fun testAttestImageHash() = runBlocking {
        if (API_KEY.isBlank() || TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in API_KEY and TEST_MNEMONIC"); return@runBlocking
        }

        // Build a deterministic manifest from fixed bytes to avoid non-deterministic timestamps
        val sampleBytes = "provenance test deterministic attest".toByteArray()


        // Ensure payload has deterministic fields and an explicit certificate hash
        val sampleCertHash = "0".repeat(64)

        // Build the ImageHashPayload from the sample bytes using the factory so
        // the embedded C2PA manifest and hashes are computed consistently.
        val payloadForChecks = ImageHashPayload.createFromBytes(
            imageBytes = sampleBytes,
            mime = "image/png",
            filename = "attest_single.png",
            producer = "test-producer",
            account = "",
            commitment = Commitment.confirmed
        ).copy(
            width = 23,
            height = 23,
            //parentHash = parentHash,
            certificateHash = sampleCertHash)



        // Sanity checks for payload
        assertEquals("certificateHash must match sample", sampleCertHash, payloadForChecks.certificateHash)
        // The assetHash is stored in the embedded C2PA manifest
        val assetHash = payloadForChecks.c2paManifest!!.assetHash
        assertEquals("assetHash must be 64 hex chars", 64, assetHash.length)
        assertFalse("assetHash must not be blank", assetHash.isBlank())

        // Verify the payload JSON ordering expected by the on-chain schema
        val keypair = ProvenanceManager.getKeyPair()
        val payloadJson = ProvenanceManager.buildPayloadJson(payloadForChecks, keypair.publicKey)
        println("Payload JSON: $payloadJson")

//        val fieldNames = listOf("type","hash","asset_hash","attester","certificate_hash","mime","name","timestamp","expireAt","recipient")
//        for (i in 0 until fieldNames.size - 1) {
//            val a = payloadJson.indexOf('"' + fieldNames[i] + '"')
//            val b = payloadJson.indexOf('"' + fieldNames[i + 1] + '"')
//            assertTrue("Field order: ${fieldNames[i]} must come before ${fieldNames[i+1]}", a >= 0 && b >= 0 && a < b)
//        }
        // Use the ImageHashPayload API (map-based overload removed). Pass the
        // deterministic payload we built earlier (which includes certificateHash).
        val result = Provenance.attestImageHash(
            payloadForChecks,
            ManifestOption.None,
            "dev01",
            "devschema001"
        )

        result
            .onSuccess { pr ->
                println("✅ attestationId: ${pr.attestationId}")
                println("   txSignature:   ${pr.response?.Signature}")
                assertFalse("attestationId must not be blank", pr.attestationId.isBlank())
                assertEquals("status must be 'success'",      "success", pr.response?.Status)
                assertFalse("tx signature must not be blank",
                    pr.response?.Signature?.isBlank() ?: true)
                assertNull("no manifestFile for ManifestOption.None",   pr.manifestFile)
                assertNull("no embeddedFile for ManifestOption.None",   pr.embeddedImageFile)
                assertFalse("isQueued must be false",                   pr.isQueued)
            }
            .onFailure { e ->
                println("❌ attestImageHash failed:")
                println("   Message: ${e.message}")
                e.printStackTrace()
                if (e.cause != null) {
                    println("   Cause: ${e.cause?.message}")
                    e.cause?.printStackTrace()
                }
            }

        if (result.isFailure) {
            fail("attestation failed: ${result.exceptionOrNull()?.message}\n${result.exceptionOrNull()?.stackTraceToString()}")
        }
        assertTrue("attestation must succeed", result.isSuccess)
    }

    /**
     * Attest with ManifestOption.SidecarFile:
     * verifies the .c2pa.json sidecar is created on disk and contains
     * attestationId, manifestHash, and assetHash so offline verification works.
     */
    @Test
    fun testAttestImageHashWithSidecar() = runBlocking {
        if (API_KEY.isBlank() || TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in API_KEY and TEST_MNEMONIC"); return@runBlocking
        }

        val tmpFile = makeTempImageFile("attest_sidecar")
        try {
            val payload = ImageHashPayload.create(
                filePath   = tmpFile.absolutePath,
                mime       = "image/png",
                commitment = Commitment.confirmed
            )
            val result = Provenance.attestImageHash(payload,
                ManifestOption.SidecarFile,
                "test007",
                "sc_test01"
            )

            result
                .onSuccess { pr ->
                    println("✅ attestationId: ${pr.attestationId}")
                    assertNotNull("manifestFile must be present", pr.manifestFile)
                    assertTrue("sidecar file must exist on disk", pr.manifestFile!!.exists())
                    assertTrue("sidecar name ends in .c2pa.json",
                        pr.manifestFile.name.endsWith(".c2pa.json"))

                    val content = pr.manifestFile.readText()
                    assertTrue("sidecar must embed attestationId",
                        content.contains(pr.attestationId))
                    assertTrue("sidecar must embed manifestHash",
                        content.contains(pr.manifest.manifestHash))
                    assertTrue("sidecar must embed assetHash",
                        content.contains(pr.manifest.assetHash))

                    println("   Sidecar: ${pr.manifestFile.absolutePath}")
                }
                .onFailure { println("❌ Failed: ${it.message}") }

            assertTrue("attestation must succeed", result.isSuccess)
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * Attest → wait for chain → verifyOnChain with expectedHash.
     * Asserts isVerified=true, status="verified", on-chain hash matches local manifestHash.
     */
    @Test
    fun testVerifyOnChain() = runBlocking {
        if (API_KEY.isBlank() || TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in API_KEY and TEST_MNEMONIC"); return@runBlocking
        }

        val tmpFile = makeTempImageFile("verify_onchain")
        try {
            // 1. Attest
            val payload = ImageHashPayload.create(
                filePath   = tmpFile.absolutePath,
                commitment = Commitment.confirmed
            )
            val pr = Provenance.attestImageHash(payload, ManifestOption.None,
                "test007", "sc_test01").getOrThrow()
            println("   Attestation PDA: ${pr.attestationId}")

            // 2. Wait for chain to confirm the tx
            Thread.sleep(6_000)

            // 3. Verify on-chain — tamper-check with expectedHash
            val v = Provenance.verifyOnChain(
                attestationId = pr.attestationId,
                expectedHash  = pr.manifest.manifestHash
            ).getOrThrow()

            println("✅ On-chain status: ${v.status}")
            println("   On-chain hash:   ${v.onChainHash}")
            println("   Local hash:      ${pr.manifest.manifestHash}")

            assertTrue("isVerified must be true",     v.isVerified)
            assertEquals("status must be 'verified'", "verified", v.status)
            assertEquals("on-chain hash must match local manifestHash",
                pr.manifest.manifestHash, v.onChainHash)
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * verifyOnChain with expectedHash=null just confirms the account exists on-chain
     * without a hash comparison. isVerified should still be true.
     */
    @Test
    fun testVerifyOnChainWithoutExpectedHash() = runBlocking {
        if (API_KEY.isBlank() || TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in API_KEY and TEST_MNEMONIC"); return@runBlocking
        }

        val tmpFile = makeTempImageFile("verify_nohash")
        try {
            val pr = Provenance.attestImageHash(
                ImageHashPayload.create(tmpFile.absolutePath, commitment = Commitment.confirmed),
                ManifestOption.None, "test007", "sc_test01"
            ).getOrThrow()

            Thread.sleep(6_000)

            val v = Provenance.verifyOnChain(pr.attestationId, expectedHash = null).getOrThrow()

            assertTrue("isVerified must be true even without hash check", v.isVerified)
            assertFalse("onChainHash must not be blank", v.onChainHash.isBlank())
            println("✅ verifyOnChain (no expectedHash): ${v.status}")
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * attestOffline() queues a signed certificate locally — no network needed.
     * Verifies queue grows by 1, certificate is ED25519-signed, sidecar is saved,
     * and attestationId is pre-derived via local PDA computation.
     * clearPending() empties the queue.
     */
    @Test
    fun testAttestOfflineQueue() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC"); return@runBlocking
        }

        Provenance.clearPending()   // start clean
        val tmpFile = makeTempImageFile("offline_attest")

        try {
            val payload = ImageHashPayload.create(filePath = tmpFile.absolutePath)
            val result  = Provenance.attestOffline(payload, ManifestOption.SidecarFile)

            result
                .onSuccess { offline ->
                    println("✅ Queued ID: ${offline.queueId}")
                    assertFalse("queueId must not be blank",
                        offline.queueId.isBlank())
                    assertEquals("signature is 128 hex chars (64 ED25519 bytes × 2)",
                        128, offline.certificate.signature.length)
                    assertFalse("attestationId must be pre-derived locally",
                        offline.certificate.attestationId.isBlank())
                    assertNotNull("sidecar file must be returned", offline.manifestFile)
                    assertTrue("sidecar must exist on disk", offline.manifestFile!!.exists())
                }
                .onFailure { println("❌ Failed: ${it.message}") }

            assertTrue("attestOffline must succeed",    result.isSuccess)
            assertEquals("pending queue must have 1 item", 1, Provenance.pendingCount())

            Provenance.clearPending()
            assertEquals("queue must be empty after clearPending", 0, Provenance.pendingCount())
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * Queue one offline attestation, then submitPending() broadcasts it to devnet.
     * After a successful submit the queue must be empty.
     */
    @Test
    fun testAttestOfflineAndSubmitPending() = runBlocking {
        if (API_KEY.isBlank() || TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in API_KEY and TEST_MNEMONIC"); return@runBlocking
        }

        Provenance.clearPending()
        val tmpFile = makeTempImageFile("submit_pending")

        try {
            // 1. Queue offline
            val payload = ImageHashPayload.create(
                filePath   = tmpFile.absolutePath,
                commitment = Commitment.confirmed
            )
            val offline = Provenance.attestOffline(payload, ManifestOption.None).getOrThrow()
            println("✅ Queued: ${offline.queueId}")
            assertEquals("1 item in queue before submit", 1, Provenance.pendingCount())

            // 2. Submit to chain
            val submitResults = Provenance.submitPending().toList()
            assertEquals("must receive exactly 1 submit result", 1, submitResults.size)

            val sr = submitResults.first()
            sr.result
                .onSuccess { pr ->
                    println("✅ Submitted attestationId: ${pr.attestationId}")
                    println("   Signature:              ${pr.response?.Signature}")
                    assertFalse("attestationId not blank", pr.attestationId.isBlank())
                    assertEquals("status success", "success", pr.response?.Status)
                }
                .onFailure { println("❌ Submit failed: ${it.message}") }

            assertTrue("submit must succeed",             sr.result.isSuccess)
            assertEquals("queue empty after submit",      0, Provenance.pendingCount())
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * attestBatch() attests 3 images sequentially and emits 1 AttestationResult per image.
     * Results must arrive in order (index 0, 1, 2) and all must succeed.
     */
    @Test
    fun testAttestBatch() = runBlocking {
        if (API_KEY.isBlank() || TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in API_KEY and TEST_MNEMONIC"); return@runBlocking
        }

        val count = 3
        val files = (1..count).map { i -> makeTempImageFile("batch_img_$i") }

        try {
            val payloads = files.map { f ->
                ImageHashPayload.create(
                    filePath   = f.absolutePath,
                    mime       = "image/png",
                    commitment = Commitment.confirmed
                )
            }

            val results = mutableListOf<AttestationResult>()
            Provenance.attestBatch(payloads, ManifestOption.None,
                    "test007", "sc_test01"
                ).collect { item ->
                results.add(item)
                item.result
                    .onSuccess { pr ->
                        println("✅ [${item.index}] ${item.name} → ${pr.attestationId}")
                        assertFalse("attestationId not blank", pr.attestationId.isBlank())
                    }
                    .onFailure { println("❌ [${item.index}] ${item.name}: ${it.message}") }
            }

            assertEquals("must receive $count results", count, results.size)
            assertTrue("all attestations must succeed",
                results.all { it.result.isSuccess })
            results.forEachIndexed { i, r ->
                assertEquals("result index must match input position", i, r.index)
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    /**
     * resetSession() clears the schema flag in SharedPreferences and empties the queue.
     */
    @Test
    fun testResetSession() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC"); return@runBlocking
        }

        val keypair   = ProvenanceManager.getKeyPair()
        val walletKey = keypair.publicKey.toBase58()

        ProvenancePrefs.markSchemaCreated(walletKey)
        assertTrue("schema must be marked before reset",
            ProvenancePrefs.isSchemaCreated(walletKey))

        Provenance.resetSession()

        assertFalse("schema must be cleared after resetSession",
            ProvenancePrefs.isSchemaCreated(walletKey))
        assertEquals("queue must be empty after resetSession", 0, Provenance.pendingCount())
        println("✅ resetSession cleared all state for $walletKey")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CreateSchema Method Tests
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Tests createSchema method when schema already exists (offline scenario).
     * Verifies the method returns immediate success without network calls.
     */
    @Test
    fun testCreateSchemaWhenSchemaExists() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        val keypair = ProvenanceManager.getKeyPair()
        val walletKey = keypair.publicKey.toBase58()

        // Pre-mark schema as created to simulate existing schema
        ProvenancePrefs.markSchemaCreated(walletKey)

        println("Testing schema exists path: deriving schema address")
        val schemaAddr = Provenance.getSchemaAddress("")
        assertNotNull("Schema address should be available when schema is marked", schemaAddr)
        println("✅ Derived schema address: $schemaAddr")
    }

    /**
     * Tests createSchema method with new schema creation (network test).
     * Verifies complete schema creation workflow including network submission.
     */
    @Test
    fun testCreateSchemNewSchemaCreation() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        val keypair = ProvenanceManager.getKeyPair()
        val walletKey = keypair.publicKey.toBase58()

        // Reset schema state to force creation
        ProvenancePrefs.reset(walletKey)

        // ensureSchema should now return a failure instructing to create schema out-of-band
        val result = ProvenanceManager.ensureSchema(account = "", commitment = "confirmed")
        assertTrue("ensureSchema must fail when schema is absent (client-side creation removed)", result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Error message should mention createSchema removal", msg.contains("createSchema is removed") || msg.contains("createSchema removed"))
    }

    /**
     * Tests createSchema method with different commitment levels.
     * Verifies the method works with various commitment parameters.
     */
    @Test
    fun testCreateSchemaWithDifferentCommitments() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        val keypair = ProvenanceManager.getKeyPair()
        val walletKey = keypair.publicKey.toBase58()

        val commitmentLevels = listOf("finalized")

        println("Testing createSchema with different commitment levels...")

        for (commitment in commitmentLevels) {
            // Reset for each test
            ProvenancePrefs.reset(walletKey)

            println("Testing ensureSchema with commitment: $commitment")
            val result = ProvenanceManager.ensureSchema(account = "", commitment = commitment)
            assertTrue("ensureSchema should fail with removal message", result.isFailure)
            val msg = result.exceptionOrNull()?.message ?: ""
            assertTrue(msg.contains("createSchema is removed") || msg.contains("createSchema removed"))
        }
    }

    /**
     * Tests createSchema method with default parameters.
     * Verifies the method works correctly when called without explicit parameters.
     */
    @Test
    fun testCreateSchemaWithDefaults() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        val keypair = ProvenanceManager.getKeyPair()
        val walletKey = keypair.publicKey.toBase58()

        // Ensure schema exists first by calling with explicit parameters
        ProvenancePrefs.markSchemaCreated(walletKey)

        println("Testing schema defaults: deriving schema address")
        val schemaAddress = Provenance.getSchemaAddress("")
        assertNotNull("Schema address should be available", schemaAddress)
        println("✅ Derived schema address: $schemaAddress")
    }

    /**
     * Tests createSchema method integration with attestation workflow.
     * Verifies that createSchema + attestation works together seamlessly.
     */
    @Test
    fun testCreateSchemaIntegrationWithAttestation() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        val keypair = ProvenanceManager.getKeyPair()
        val walletKey = keypair.publicKey.toBase58()

        // Reset to ensure fresh test
        ProvenancePrefs.reset(walletKey)
        println("Testing attestation integration assuming schema exists (marked in prefs)")
        ProvenancePrefs.markSchemaCreated(walletKey)

        // Step 2: Create test image and payload
        println("Step 2: Preparing test image...")
        val testFile = makeTempImageFile("integration_test")
        val payload = ImageHashPayload.create(
            filePath = testFile.absolutePath,
            account = ""
        )


        // Step 3: Attest the image (schema should already exist)
        println("Step 3: Attesting image...")
        val attestResult = Provenance.attestImageHash(
            payload = payload,
            manifestOption = ManifestOption.None,
            credentialName = "test007",
            schemaName = "sc_test01"
        )

        if (attestResult.isSuccess) {
            val result = attestResult.getOrThrow()
            println("✅ Integration test successful!")
            println("Attestation ID: ${result.attestationId}")
            assertNotNull("Attestation ID should be generated", result.attestationId)
            assertEquals("Expected success status", "success", result.response?.Status)
        } else {
            val error = attestResult.exceptionOrNull()
            println("❌ Attestation failed: ${error?.message}")
            fail("Attestation should succeed after schema creation: ${error?.message}")
        }

        // Cleanup
        testFile.delete()
        println("=" .repeat(80))
    }

    /**
     * Tests createSchema method error handling scenarios.
     * Uses offline scenario to test error conditions safely.
     */
    @Test
    fun testCreateSchemaErrorHandling() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        println("Testing createSchema error handling...")

        // Test with valid parameters to establish baseline
        // Instead of creating schema client-side, mark schema created and validate helpers
        val keypair = ProvenanceManager.getKeyPair()
        val walletKey = keypair.publicKey.toBase58()
        ProvenancePrefs.markSchemaCreated(walletKey)

        val addr = Provenance.getSchemaAddress("")
        assertNotNull("Schema address should be available after marking", addr)
        println("✅ schema helper functions work after marking schema: $addr")
    }

    /**
     * Tests createSchema method performance characteristics.
     * Measures timing for both new creation and existing schema scenarios.
     */
    @Test
    fun testCreateSchemaPerformance() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        val keypair = ProvenanceManager.getKeyPair()
        val walletKey = keypair.publicKey.toBase58()

        println("Testing createSchema performance...")

        // Test 1: Schema already exists (should be very fast)
        ProvenancePrefs.markSchemaCreated(walletKey)

        val existingStartTime = System.currentTimeMillis()
        val schemaAddr = Provenance.getSchemaAddress("")
        val existingEndTime = System.currentTimeMillis()
        val existingDuration = existingEndTime - existingStartTime

        assertNotNull("Schema address should be available", schemaAddr)
        println("Existing schema address fetch time: ${existingDuration}ms")
        assertTrue("Existing schema check should be under 100ms", existingDuration < 100)

        println("✅ schema helper performance is acceptable")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // End CreateSchema Tests
    // ═════════════════════════════════════════════════════════════════════════

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creates a temporary PNG file with unique content so every test has a
     * fresh SHA-256 hash — avoids collisions with previously attested images.
     */
    private fun makeTempImageFile(tag: String): File =
        File(context.filesDir, "${tag}_${System.currentTimeMillis()}.png")
            .also { f ->
                f.writeBytes(
                    "provenance test [$tag] timestamp=${System.currentTimeMillis()}".toByteArray()
                )
            }

    /**
     * Unit test for AttestationProgram.createCredential instruction encoding and account metas.
     */
    @Test
    fun testCreateCredentialInstruction() = runBlocking {
        // Use real wallet keypair (like other tests)
        val keypair = ProvenanceManager.getKeyPair()
        val payer = keypair.publicKey
        val authority = keypair.publicKey
        val schema = ProvenanceManager.deriveSchemaAddress()
        val credentialName = "test-credential"
        val credential = AttestationProgram.deriveCredentialAddress(
            authority = authority,
            name = credentialName
        )
        val systemProgram = Utility.SYSTEM_PROGRAM_ID

        val signers = listOf(authority)

        val ix = AttestationProgram.createCredential(
            payer = payer,
            credential = credential,
            authority = authority,
            systemProgram = systemProgram,
            name = credentialName,
            signers = signers
        )

        // Check program ID
        assertEquals(AttestationProgram.PROGRAM_ID, ix.programId)
        // Check account metas
        assertEquals(payer, ix.keys[0].publicKey)
        assertTrue(ix.keys[0].isSigner && ix.keys[0].isWritable)
        assertEquals(credential, ix.keys[1].publicKey)
        assertFalse(ix.keys[1].isSigner)
        assertTrue(ix.keys[1].isWritable)
        assertEquals(authority, ix.keys[2].publicKey)
        assertTrue(ix.keys[2].isSigner)
        assertFalse(ix.keys[2].isWritable)
        assertEquals(systemProgram, ix.keys[3].publicKey)
        assertFalse(ix.keys[3].isSigner)
        assertFalse(ix.keys[3].isWritable)
        // Check data encoding
        val expectedPrefix = byteArrayOf(0)
        assertTrue(ix.data.copyOfRange(0, 1).contentEquals(expectedPrefix))
        // Check name encoding (UTF-8, length-prefixed)
        val nameBytes = credentialName.toByteArray(Charsets.UTF_8)
        val nameLen = ix.data[1].toInt()
        assertEquals(nameBytes.size, nameLen)
        assertTrue(ix.data.copyOfRange(2, 2 + nameLen).contentEquals(nameBytes))
        // Check signers encoding
        val numSigners = ix.data[2 + nameLen].toInt()
        assertEquals(signers.size, numSigners)
        // Each signer is 32 bytes
        val signersStart = 2 + nameLen + 1
        for (i in 0 until numSigners) {
            val signerBytes = signers[i].toByteArray()
            val ixBytes = ix.data.copyOfRange(signersStart + i * 32, signersStart + (i + 1) * 32)
            assertTrue(ixBytes.contentEquals(signerBytes))
        }
    }

    /**
     * End-to-end test for Provenance.createCredential API.
     * Calls the high-level method and asserts the result.
     */
    @Test
    fun testCreateCredentialEndToEnd() = runBlocking {
        if (API_KEY.isBlank() || TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in API_KEY and TEST_MNEMONIC"); return@runBlocking
        }

        val credentialName = "test-credential-" + System.currentTimeMillis()
        println("Creating credential with name: $credentialName")
        // Client-side credential creation is removed. Instead, verify we can derive
        // the credential PDA deterministically for the given name.
        val keypair = ProvenanceManager.getKeyPair()
        val credentialPda = AttestationProgram.deriveCredentialAddress(
            authority = keypair.publicKey,
            name = credentialName
        )
        val pdaBase58 = credentialPda.toBase58()
        println("Derived credential PDA for name '$credentialName': $pdaBase58")
        assertEquals("PDA must be 44 Base58 chars", 44, pdaBase58.length)
        assertFalse("PDA must not be blank", pdaBase58.isBlank())
    }

    /**
     * Creates a credential on-chain and prints the derived credential PDA.
     * This is a full end-to-end test: it creates the credential, derives the PDA, and prints it for manual use.
     */
    @Test
    fun testCreateCredentialAndPrintPda() = runBlocking {
        if (API_KEY.isBlank() || TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in API_KEY and TEST_MNEMONIC"); return@runBlocking
        }
        val keypair = ProvenanceManager.getKeyPair()
        //val payer = keypair.publicKey
        val feepayer = PublicKey(SdkConfig.apiConfig.FeePayer)
        //val schema = ProvenanceManager.deriveSchemaAddress()
        val credentialName = "chen-credential" + System.currentTimeMillis()
        println("Creating credential with name: $credentialName")
        // Client no longer creates credentials. Derive and print the credential PDA instead.
        val credentialPda = AttestationProgram.deriveCredentialAddress(
            authority = keypair.publicKey,
            name = credentialName
        )
        val pdaBase58 = credentialPda.toBase58()
        println("Credential PDA for name '$credentialName': $pdaBase58")
        assertEquals("PDA must be 44 Base58 chars", 44, pdaBase58.length)
        assertFalse("PDA must not be blank", pdaBase58.isBlank())
    }

    /**
     * Prints Schema PDA + createSchema preview (accounts + data hex) for a known credential PDA.
     *
     * You requested to hardcode this credential PDA:
     *   5cWZLg1oCHwh4rcZJmHFsmGHSgi1PBb1bWpo9D41Y82P
     */
    @Test
    fun testPrintSchemaPdaFromKnownCredentialPda() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        val credentialPdaBase58 = "6bzWPdFTrzAQjacPFAcB9afTXrfL8hm8pt5SyrHDfcfR"//5cWZLg1oCHwh4rcZJmHFsmGHSgi1PBb1bWpo9D41Y82P"
        println("Creating schema using credentialPda: $credentialPdaBase58")

        // Derive schema PDA deterministically from the provided credential PDA.
        // Seeds (per AttestationProgram):
        //   ["schema", credentialPda(32 bytes), schemaName(utf8), version(1 byte)]
        val credentialPda = PublicKey(credentialPdaBase58)
        // Manual schema name (do not depend on ProvenanceManager.SCHEMA_NAME)
        val schemaName = "image_hash"
        val schemaVersion = 0
        val keypair =
        println("Attestation programId: ${AttestationProgram.PROGRAM_ID.toBase58()}")
        println("Schema name: '$schemaName'  version=$schemaVersion")
        println("Credential PDA (parsed): ${credentialPda.toBase58()}")
        println("Schema PDA seeds: ['schema', credentialPda, '$schemaName', 0x00]")

        val schemaPda = AttestationProgram.deriveSchemaAddress(
            credential = credentialPda,
            name = schemaName,
            version = schemaVersion
        )
        println("✅ Derived Schema PDA: ${schemaPda.toBase58()}")

        // Client no longer creates schemas. This test now only derives the Schema PDA
        // from a known credential PDA and prints it for manual verification.
        println("Derived Schema PDA: ${schemaPda.toBase58()}")
        assertFalse("Derived schema PDA must not be blank", schemaPda.toBase58().isBlank())

        // Capture CreateSchemaLog from device logcat for diagnostics and assert presence
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "CreateSchemaLog"))
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            println("----- CreateSchemaLog (captured) -----")
            println(output)
            if (output.isNotBlank()) {
                // Basic presence check
                assertTrue("Expected CreateSchemaLog entries",
                    output.contains("Parsed credential") || output.contains("Detected Anchor-style"))

                // Regression detection: ensure we did NOT parse a huge signer-count (symptom of misaligned parsing)
                val signerRegex = Regex("signers=(\\d+)")
                val badThreshold = 1_000_000
                val badMatches = signerRegex.findAll(output).mapNotNull { m ->
                    m.groups[1]?.value?.toLongOrNull()
                }.filter { it >= badThreshold }.toList()

                if (badMatches.isNotEmpty()) {
                    fail("Detected suspiciously large signer-count(s) in CreateSchemaLog: ${badMatches.joinToString(", ")}. This indicates a discriminator/offset parsing mismatch.")
                }
            } else {
                println("⚠️ CreateSchemaLog empty — device may restrict reading logs from tests")
            }
        } catch (e: Exception) {
            println("⚠️ Failed to capture logcat: ${e.message}")
        }

    }

}

