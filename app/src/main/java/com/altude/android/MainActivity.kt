package com.altude.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.altude.android.ui.theme.AltudesdkTheme
import com.altude.core.model.KeyPair
import com.altude.gasstation.Altude
import kotlinx.coroutines.runBlocking


class MainActivity : ComponentActivity() {

    override  fun onCreate(savedInstanceState: Bundle?) = runBlocking {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ✅ Call the suspend transferToken
//        lifecycleScope.launch {
//            GasStationSdk.transferToken(
//                TransferOptions(
//                    source = "YourSourceWalletAddress",
//                    destination = "DestinationWalletAddress",
//                    amount = 1.0,
//                    mintToken = Token.USDC
//                )
//            ){ result ->
//                result
//                    .onSuccess { println("✅ Sent: $it") }
//                    .onFailure { println("❌ Failed: ${it.message}") }
//            }
//        }

//        Altude .setApiKey(this,"myAPIKey")
//        val keyPair = Altude.generateKeyPair()


        val keyPair = Altude.generateKeyPair()
        //KeyPair.
        setContent {
            AltudesdkTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AltudesdkTheme {
        Greeting("Android")
    }
}