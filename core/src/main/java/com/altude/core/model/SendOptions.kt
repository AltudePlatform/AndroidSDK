package com.altude.core.model

interface SendOptions {
    val source: String
    val destination: String
    val amount: Double
    val mint: String
}
