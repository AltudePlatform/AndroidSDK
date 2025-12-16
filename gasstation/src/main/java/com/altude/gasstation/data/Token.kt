package com.altude.gasstation.data

import com.altude.core.config.SdkConfig

enum class Token(val mainnet: String, val devnet: String) {
    SOL(
        mainnet = "So11111111111111111111111111111111111111112",
        devnet  = "So11111111111111111111111111111111111111112"
    ),
    USDT(
        mainnet = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
        devnet  = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" // no official devnet USDT
    ),
    USDC(
        mainnet = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        devnet  = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    ),
    LINK(
        mainnet = "2wpTofQ8SkACrk5xNdqk1ZUidPDZcN6h4FJfB9RkqWxN",
        devnet  = ""
    ),
    RNDR(
        mainnet = "RnwpAb5AF5JZKt4dFHb2wAHXQ2VuqsQvjA5PwMZKzp",
        devnet  = ""
    ),
    WIF(
        mainnet = "2gcXhWhChhAAXSw1esijF7LfopdSbCCGzG04wTtdBSS",
        devnet  = ""
    ),
    GRT(
        mainnet = "AZZre0B3UbkGwbUtEsqSiLDsDU2M7co26g7Egezheh",
        devnet  = ""
    ),
    BONK(
        mainnet = "8HXycfvhrtRpyL9WcnjxpQfQMsXik6jhbSahQXegaEg",
        devnet  = ""
    ),
    AR(
        mainnet = "6zHcd4Z3YAH8PfQ4Niupqv3MFf2kcKEDLRRUKn4rG",
        devnet  = ""
    ),
    PYTH(
        mainnet = "8mQo4EG4m4sEH9D5cFsw9mqqmZbofzjysA8HkRXsLtwY",
        devnet  = ""
    ),
    KIN(
        mainnet = "kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6",
        devnet  = "KinDesK3dYWo3R2wDk6Ucaf31tvQCCSYyL8Fuqp33GX"
    ),
    SRM(
        mainnet = "ByRSn9zspZBkx4nW5z8tYtzwdRKj1jpkvxS73VrMLpGs",
        devnet  = ""
    ),
    RAY(
        mainnet = "4k3Dyjzvzp8e3Y3T3aTuw5uYZV3y2QwHyM1Y6vkmXX5g",
        devnet  = ""
    ),

    // From the image (based on logo-to-mint mapping):
    JUP(
        mainnet = "JUP4Fb2cqiRUcaTHdrPC8h2gNsA2ETXiPDD33WcGuJB",
        devnet  = ""
    ),
    W(
        mainnet = "CtMyv8AYUqK7xNu8J1yGMA6tMKuoLn28GNeRxGC2nPLs",
        devnet  = ""
    ),
    DUST(
        mainnet = "DUSTawucrTsGU8hcqRdHDCbuYhCPADMLM2VcCb8VnFnQ",
        devnet  = ""
    ),
    DOG(
        mainnet = "DoggyWyhFf7as3xyDRZUGvNUuKmbw3utuk7XKbtFd7qS",
        devnet  = ""
    ),
    WEN(
        mainnet = "Wen2BVEdc8vxbsTddwzD2mN7VPS6K6Yw1o7am7kkKD88",
        devnet  = ""
    ),
    SHDW(
        mainnet = "SHDWzGwna7gWm1piNDuZsrgWJMtDsb4WPZ3P3nKjhCg",
        devnet  = ""
    ),
    HADES(
        mainnet = "HadesWrTPLE6q8sL1oN4zXkUciVHh24vMKTbTPV85Us",
        devnet  = ""
    ),
    CASH(
        mainnet = "CASHx9KJUStyftLFWGvEVf59SGeG9sh5FfcnZMVPCASH",
        devnet  = ""
    );


    fun mint(): String = if (SdkConfig.apiConfig.RpcEnvironment.lowercase() == "devnet") (devnet.ifEmpty { mainnet }) else mainnet
}