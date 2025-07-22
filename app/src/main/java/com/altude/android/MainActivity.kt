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
import com.altude.gasstation.GasStationSdk


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        GasStationSdk.setApiKey("")

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