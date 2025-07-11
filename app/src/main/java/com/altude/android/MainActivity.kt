package com.altude.android

import android.os.Bundle
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import com.altude.android.ui.theme.AltudesdkTheme



class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

//        GasStationSdk.setApiKey("")
//
//        // ✅ Call the suspend transferToken
//        lifecycleScope.launch {
//            try {
//                val options = TransferOptions(
//                    source = "BMRmo31USZuEga32JTk1Ub242JGcod982JtmynMK3fqv",
//                    destination = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
//                    amount = 0.0001, // Amount in smallest unit (e.g. lamports)
//                    Token.SOL
//                )
//
//                GasStationSdk.transferToken(options)
//            } catch (e: Exception) {
//                Log.e("GasStationTest", "Error: ${e.message}", e)
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