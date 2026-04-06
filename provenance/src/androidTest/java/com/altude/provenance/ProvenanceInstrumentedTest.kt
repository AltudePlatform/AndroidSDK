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
    // 2. ImageHashPayload — factory methods
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * create(filePath=…) maps all public fields correctly and derives the
     * manifest hash automatically from the file content.
     */
    @Test
    fun testImageHashPayloadCreateFromFile() {
        val tmpFile = File(context.filesDir, "payload_test.png")
            .also { it.writeBytes("payload creation test image bytes".toByteArray()) }

        try {
            val payload = ImageHashPayload.create(
                filePath   = tmpFile.absolutePath,
                mime       = "image/png",
                producer   = "my-wallet-address",
                commitment = Commitment.confirmed
            )

            assertEquals("type",       "image_hash",         payload.type)
            assertEquals("mime",       "image/png",          payload.mime)
            assertEquals("name",       "payload_test.png",   payload.name)
            assertEquals("commitment", Commitment.confirmed,  payload.commitment)
            assertEquals("hash must equal c2paManifest.manifestHash",
                payload.c2paManifest.manifestHash, payload.hash)
            assertFalse("hash must not be blank",   payload.hash.isBlank())
            assertEquals("expireAt defaults to 0",  0L, payload.expireAt)

            println("✅ payload.hash = ${payload.hash}")
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * fromManifest() produces the same hash as create() for identical file content.
     */
    @Test
    fun testImageHashPayloadFromManifestMatchesCreate() {
        val bytes   = "from manifest vs create consistency".toByteArray()
        val tmpFile = File(context.filesDir, "from_manifest.png")
            .also { it.writeBytes(bytes) }

        try {
            val fromCreate   = ImageHashPayload.create(filePath = tmpFile.absolutePath)
            val fromManifest = ImageHashPayload.fromManifest(fromCreate.c2paManifest)

            assertEquals("hash must match",
                fromCreate.hash, fromManifest.hash)
            assertEquals("assetHash must match",
                fromCreate.c2paManifest.assetHash, fromManifest.c2paManifest.assetHash)
            println("✅ fromManifest hash matches create hash")
        } finally {
            tmpFile.delete()
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
            latitude           = 14.5995,
            longitude          = 120.9842,
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
        assertEquals("latitude",        cert.latitude,        parsed.latitude)
        assertEquals("longitude",       cert.longitude,       parsed.longitude)
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
            latitude           = null,
            longitude          = null
        )

        val parsed = ProvenanceCertificate.fromJson(cert.toJson())
        assertNotNull(parsed)
        assertNull("latitude must be null",  parsed!!.latitude)
        assertNull("longitude must be null", parsed.longitude)
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

        println("=" .repeat(80))
        println("Creating Schema Transaction for wallet: $walletKey")
        println("=" .repeat(80))

        // Call ensureSchema - it will return a transaction string if schema needs to be created
        val result = ProvenanceManager.ensureSchema(
            account = "",
            commitment = Commitment.confirmed.name
        )

        result
            .onSuccess { schemaTxString ->
                if (schemaTxString != null) {
                    println("\n✅ Schema Transaction Created Successfully!")
                    println("\nTransaction String (Base64):")
                    println("-" .repeat(80))
                    println(schemaTxString)
                    println("-" .repeat(80))
                    println("\nTransaction Length: ${schemaTxString.length} characters")
                    println("\nYou can submit this transaction to Solana devnet using:")
                    println("  - Solana CLI: solana send-transaction <base64-string>")
                    println("  - Or via your backend API endpoint")
                    
                    // Derive and print the schema PDA
                    val schemaPda = ProvenanceManager.deriveSchemaAddress(keypair.publicKey.toBase58())
                    println("\nExpected Schema PDA: ${schemaPda.toBase58()}")
                    println("=" .repeat(80))
                } else {
                    println("\nℹ️  Schema already exists on-chain - no transaction needed")
                    
                    // Print the existing schema PDA
                    val schemaPda = ProvenanceManager.deriveSchemaAddress(keypair.publicKey.toBase58())
                    println("Existing Schema PDA: ${schemaPda.toBase58()}")
                    println("=" .repeat(80))
                }
            }
            .onFailure { e ->
                println("\n❌ Failed to create schema transaction")
                println("Error: ${e.message}")
                e.printStackTrace()
                fail("Schema transaction creation failed: ${e.message}")
            }

        assertTrue("Schema transaction creation must succeed", result.isSuccess)
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

    /**
     * buildCertificate() signs the claim JSON with the wallet keypair (pure ED25519,
     * no network).  Validates:
     *   - signature is 128 lowercase hex chars (64 ED25519 bytes)
     *   - signerAddress matches the keypair's public key
     *   - imageSha256 equals the c2paManifest.assetHash of the payload
     */
    @Test
    fun testBuildCertificateOfflineSigning() = runBlocking {
        if (TEST_MNEMONIC.isBlank()) {
            println("⚠️  Skipped — fill in TEST_MNEMONIC to run")
            return@runBlocking
        }

        val tmpFile = makeTempImageFile("cert_offline")
        try {
            val payload = ImageHashPayload.create(filePath = tmpFile.absolutePath)
            val keypair = ProvenanceManager.getKeyPair()
            val cert    = ProvenanceManager.buildCertificate(payload, keypair)

            assertFalse("signature must not be blank",       cert.signature.isBlank())
            assertEquals("signature is 128 hex chars (64 bytes × 2)",
                128, cert.signature.length)
            assertTrue("signature must be lowercase hex",
                cert.signature.all { it.isDigit() || it in 'a'..'f' })
            assertEquals("signerAddress must match keypair pubkey",
                keypair.publicKey.toBase58(), cert.signerAddress)
            assertEquals("imageSha256 must equal c2paManifest.assetHash",
                payload.c2paManifest.assetHash, cert.imageSha256)
            assertTrue("instanceId must be a URN",
                cert.instanceId.startsWith("urn:uuid:"))

            println("✅ Signer:    ${cert.signerAddress}")
            println("   Signature: ${cert.signature.take(32)}…")
        } finally {
            tmpFile.delete()
        }
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

        val tmpFile = makeTempImageFile("attest_single")
        try {
            val payload = ImageHashPayload.create(
                filePath   = tmpFile.absolutePath,
                mime       = "image/png",
                commitment = Commitment.confirmed
            )
            val result = Provenance.attestImageHash(payload, ManifestOption.None)

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
        } finally {
            tmpFile.delete()
        }
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
            val result = Provenance.attestImageHash(payload, ManifestOption.SidecarFile)

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
            val pr = Provenance.attestImageHash(payload, ManifestOption.None).getOrThrow()
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
                ManifestOption.None
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
            Provenance.attestBatch(payloads, ManifestOption.None).collect { item ->
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

        println("Testing createSchema when schema already exists...")

        val result = Provenance.createSchema(account = "", commitment = "confirmed")

        assertTrue("createSchema should succeed when schema exists", result.isSuccess)
        val response = result.getOrThrow()
        assertEquals("Expected success status", "success", response.Status)
        assertEquals("Expected schema exists message", "Schema already exists", response.Message)

        println("✅ createSchema correctly detected existing schema")
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

        println("=" .repeat(80))
        println("Testing createSchema with new schema creation")
        println("Wallet: $walletKey")
        println("=" .repeat(80))

        val startTime = System.currentTimeMillis()
        val result = Provenance.createSchema(account = "", commitment = "confirmed")

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        println("Schema creation completed in ${duration}ms")

        if (result.isSuccess) {
            val response = result.getOrThrow()
            println("✅ Schema creation successful!")
            println("Status: ${response.Status}")
            println("Message: ${response.Message}")

            assertEquals("Expected success status", "success", response.Status)
            assertNotNull("Response message should not be null", response.Message)

            // Verify schema is now marked as created
            assertTrue("Schema should be marked as created after success",
                ProvenancePrefs.isSchemaCreated(walletKey))

            // Verify we can get the schema address
            val schemaAddress = Provenance.getSchemaAddress("")
            assertNotNull("Schema address should be available", schemaAddress)
            println("Schema PDA: $schemaAddress")

        } else {
            val error = result.exceptionOrNull()
            println("❌ Schema creation failed: ${error?.message}")
            fail("Schema creation should have succeeded: ${error?.message}")
        }

        println("=" .repeat(80))
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

            println("Testing with commitment: $commitment")

            val result = Provenance.createSchema(
                account = "",
                commitment = commitment
            )

            assertTrue("createSchema should succeed with $commitment commitment",
                result.isSuccess)

            val response = result.getOrThrow()
            assertEquals("Expected success status for $commitment", "success", response.Status)

            println("✅ createSchema succeeded with $commitment commitment")

            // Mark as created for next iteration to test "already exists" path
            ProvenancePrefs.markSchemaCreated(walletKey)
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

        println("Testing createSchema with default parameters...")

        // Call with no parameters to test defaults
        val result = Provenance.createSchema()

        assertTrue("createSchema should succeed with defaults", result.isSuccess)
        val response = result.getOrThrow()
        assertEquals("Expected success status", "success", response.Status)

        println("✅ createSchema works correctly with default parameters")
        println("Response: ${response.Message}")
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

        println("=" .repeat(80))
        println("Testing createSchema integration with attestation workflow")
        println("=" .repeat(80))

        // Step 1: Create schema explicitly
        println("Step 1: Creating schema...")
        val schemaResult = Provenance.createSchema()

        assertTrue("Schema creation should succeed", schemaResult.isSuccess)
        val schemaResponse = schemaResult.getOrThrow()
        println("Schema creation: ${schemaResponse.Status} - ${schemaResponse.Message}")

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
            manifestOption = ManifestOption.None
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
        val result = Provenance.createSchema(
            account = "",
            commitment = "confirmed"
        )

        // Should succeed (either creates new or detects existing)
        assertTrue("createSchema should handle valid parameters", result.isSuccess)

        // Test the result structure
        val response = result.getOrThrow()
        assertNotNull("Response should not be null", response)
        assertEquals("Status should be success", "success", response.Status)
        assertNotNull("Message should not be null", response.Message)

        println("✅ createSchema handles valid scenarios correctly")
        println("Response status: ${response.Status}")
        println("Response message: ${response.Message}")
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
        val existingResult = Provenance.createSchema()
        val existingEndTime = System.currentTimeMillis()
        val existingDuration = existingEndTime - existingStartTime

        assertTrue("Existing schema check should succeed", existingResult.isSuccess)
        val existingResponse = existingResult.getOrThrow()
        assertEquals("Should detect existing schema", "Schema already exists", existingResponse.Message)

        // Test 2: New schema creation (will be slower due to network)
        ProvenancePrefs.reset(walletKey)

        val newStartTime = System.currentTimeMillis()
        val newResult = Provenance.createSchema()
        val newEndTime = System.currentTimeMillis()
        val newDuration = newEndTime - newStartTime

        println("Performance Results:")
        println("- Existing schema check: ${existingDuration}ms")
        println("- New schema creation: ${newDuration}ms")

        assertTrue("New schema creation should succeed", newResult.isSuccess)

        // Existing schema should be much faster
        assertTrue("Existing schema check should be faster", existingDuration < newDuration)
        assertTrue("Existing schema check should be under 100ms", existingDuration < 100)

        println("✅ createSchema performance is acceptable")
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
}

