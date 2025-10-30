package com.altude.gasstation.interfaces

interface ITransactionResponse{
    //@SerializedName("Status")
    val Status: String // Match C# string type

    //@SerializedName("Message")
    val Message: String

    //@SerializedName("Signature")
    val Signature: String
}