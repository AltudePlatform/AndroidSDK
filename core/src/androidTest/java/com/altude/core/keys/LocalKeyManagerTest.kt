package com.altude.core.keys

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altude.core.service.StorageService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalKeyManagerTest {
    private lateinit var keyManager: LocalKeyManager

    @Before
    fun setUp() {
        keyManager = LocalKeyManager(ApplicationProvider.getApplicationContext())
        StorageService.clearAll()
    }

    @After
    fun tearDown() {
        StorageService.clearAll()
    }

    @Test
    fun getOrCreateDefaultSignerReusesManagedAccount() = runBlocking {
        val first = keyManager.getOrCreateDefaultSigner()
        val second = LocalKeyManager(ApplicationProvider.getApplicationContext())
            .getOrCreateDefaultSigner()

        assertEquals(first.publicKey, second.publicKey)
        assertEquals(listOf(first.publicKey.toBase58()), keyManager.listAccounts())
    }

    @Test
    fun createSignerAddsAnotherManagedAccount() = runBlocking {
        val first = keyManager.getOrCreateDefaultSigner()
        val second = keyManager.createSigner()

        assertNotEquals(first.publicKey, second.publicKey)
        assertEquals(2, keyManager.listAccounts().size)
    }
}
