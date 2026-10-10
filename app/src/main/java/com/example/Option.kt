package com.example

import java.util.UUID

data class Option(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var source: String,
    var destination: String,
    var forceStop: Boolean = true,
    var packageName: String = "",
    var backup: Boolean = true,
    var fixPermissions: Boolean = true
)
