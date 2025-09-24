package com.altude.gasstation.interfaces

interface ITransactionResponse{
    //@SerializedName("Status")
    val status: String // Match C# string type

    //@SerializedName("Message")
    val message: String

    //@SerializedName("Signature")
    val signature: String
}