package com.example.indoorwifi


// O tu paquete correcto

data class SalonInfo(
    val nombreSalon: String,
    var muestrasDeHuellas: MutableList<List<WifiFingerprint>>,
    var timestampUltimaMuestra: Long
)