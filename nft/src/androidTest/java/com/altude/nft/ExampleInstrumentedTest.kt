package com.altude.nft

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altude.core.config.SdkConfig
import com.altude.core.data.CreateNFTCollectionOption
import com.altude.core.data.MintOption
import com.altude.core.service.StorageService
import kotlinx.coroutines.runBlocking

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import org.junit.Before

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        NFTSdk.setApiKey(context,"myAPIKey")
    }

    @Test
    fun testCreateNFTCollection() = runBlocking {
        StorageService.storeMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")
        // Context of the app under test.

        val option = CreateNFTCollectionOption(
            metadataUri = "ipfs://QmagsUSwED1V3aE28hcJFHh1xqZx32FMpaukRiJUwQs7TC",
            name = "testnft1",
            sellerFeeBasisPoints = 1,

        )
        val result = NFTSdk.createNFTCollection(option)

        result
            .onSuccess { println("✅ Sent: $it") }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

        // Add an assert if needed
        assert(result.isSuccess)

    }
    @Test
    fun testMint() = runBlocking {
        StorageService.storeMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")
        //StorageService.storeMnemonic("habit online seven tortoise rhythm expect unlock abstract then horse surface wait")
        // Context of the app under test.

        val option = MintOption(
            uri = "ipfs://QmaSJapD4DVYqLkXGLQX39h8ZC14dxUCo2CRNEbe6KEEGY",
            symbol = "alt",
            name = "test1",
            owner = "",
            sellerFeeBasisPoints = 1,
            collection = "8yAVKpkdGGfRKJcT7y6hWke3xB1SaS5ghZDLjK9o2vqA" //8yAVKpkdGGfRKJcT7y6hWke3xB1SaS5ghZDLjK9o2vqA 2EnwP9qZXWzhJkGyHoETTBjBED93hPDkSCTdfdW4AJiz
        )
        val result = NFTSdk.mint(option)

        result
            .onSuccess { println("✅ Sent: $it") }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

        // Add an assert if needed
        assert(result.isSuccess)

    }
}