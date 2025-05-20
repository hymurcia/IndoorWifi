package com.example.indoorwifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStream
import kotlin.math.sqrt
import java.io.InputStreamReader // Nueva para leer el stream
import android.net.Uri
import android.widget.ScrollView
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface // Para negrita
import android.graphics.Color

class MainActivity : AppCompatActivity() {

    // --- Clases de Datos ---




    // --- Variables de UI ---

    private lateinit var textViewCurrentLocation: TextView
    private lateinit var textViewNearbySalon: TextView
    private lateinit var fabAddSalon: FloatingActionButton
    private lateinit var buttonRefreshLocation: Button
    private lateinit var textViewDebugWifiInfo: TextView
    private lateinit var buttonManageSalons: Button
    private lateinit var fabShowCredits: FloatingActionButton
    private lateinit var buttonImportJson: Button
    private lateinit var buttonExportJson: Button



    // --- Variables de Lógica ---
    private lateinit var wifiManager: WifiManager
    private var nombreSalonTemporal: String? = null
    private var esEscaneoParaLocalizacion: Boolean = false
    private val salonesMapeados = mutableListOf<SalonInfo>()
    private var reemplazarMuestrasDelSalonActual: Boolean = false
    private val SALONES_JSON_MIME_TYPE = "application/json"


    // --- Constantes ---
    companion object {
        private const val TAG = "IndoorWifiApp"
        private const val TAG_WIFI = "WIFI_SCANNING"
        private const val TAG_PERMISSIONS = "PERMISSIONS_REQUEST"
        private const val SALONES_FILENAME = "salones_data.json"
        private const val DEFAULT_RSSI_FOR_MISSING_AP = -100
        private const val K_NEIGHBORS = 5
        private const val MAX_ACCEPTABLE_DISTANCE_FOR_LOCALIZATION = 300.0 // Ajustar empíricamente
        private const val MIN_MAJORITY_FOR_HIGH_CONFIDENCE = 2 // Para K_NEIGHBORS = 3
        private const val MIN_RSSI_THRESHOLD = -87 // APs con RSSI por debajo de este valor serán ignorados. ¡Ajusta este valor!
        private const val CREATE_FILE_REQUEST_CODE = 123 // Para la exportación
        private const val PICK_FILE_REQUEST_CODE = 456   // Para la importación
        private const val SALONES_DATA_FILENAME_FOR_EXPORT = "salones_udec_data.json" // Nombre sugerido para el archivo exportado


    }


    // --- Gestión de Permisos ---
    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i(TAG_PERMISSIONS, "Permiso ACCESS_FINE_LOCATION concedido.")
                if (esEscaneoParaLocalizacion) {
                    iniciarEscaneoWifiParaLocalizacion()
                } else {
                    nombreSalonTemporal?.let { nombre ->
                        iniciarEscaneoWifiParaSalon(nombre)
                    }
                }
            } else {
                Log.e(TAG_PERMISSIONS, "Permiso ACCESS_FINE_LOCATION denegado.")
                Toast.makeText(this, "Permiso de ubicación denegado. No se puede continuar.", Toast.LENGTH_LONG).show()
                nombreSalonTemporal = null
                esEscaneoParaLocalizacion = false
            }
        }


    // --- BroadcastReceiver para resultados de Wi-Fi ---
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                } else { true }

                Log.d(TAG_WIFI, "BroadcastReceiver: SCAN_RESULTS_AVAILABLE_ACTION recibido. Success flag: $success")

                if (success) { // Idealmente procesar solo si son nuevos resultados
                    Log.d(TAG_WIFI, "Resultados del escaneo actualizados (success=true).")
                } else {
                    Log.w(TAG_WIFI, "Resultados del escaneo NO actualizados (success=false). Podrían ser datos cacheados.")
                }
                if (esEscaneoParaLocalizacion) {
                    procesarResultadosParaLocalizacion()
                } else {
                    procesarResultadosEscaneoParaMapeo()
                }
            }
        }
    }

    // --- Ciclo de Vida de la Actividad ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeUI()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        cargarSalonesDesdeJson()
        Log.d(TAG, "onCreate: Salones cargados, total: ${salonesMapeados.size}")
        setupButtonListeners()
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wifiScanReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(wifiScanReceiver, intentFilter)
            }
            Log.d(TAG_WIFI, "WiFi Scan Receiver registrado.")
        } catch (e: Exception) {
            Log.e(TAG_WIFI, "Error al registrar WiFi Scan Receiver: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(wifiScanReceiver)
            Log.d(TAG_WIFI, "WiFi Scan Receiver desregistrado.")
        } catch (e: Exception) {
            Log.e(TAG_WIFI, "Error al desregistrar WiFi Scan Receiver: ${e.message}")
        }
    }

    // --- Inicialización de UI y Listeners ---
    private fun initializeUI() {
        textViewCurrentLocation = findViewById(R.id.textViewCurrentLocation)
        textViewNearbySalon = findViewById(R.id.textViewNearbySalon)
        fabAddSalon = findViewById(R.id.fabAddSalon)
        fabShowCredits = findViewById(R.id.fabShowCredits)
        buttonRefreshLocation = findViewById(R.id.buttonRefreshLocation)
        textViewDebugWifiInfo = findViewById(R.id.textViewDebugWifiInfo)
        buttonManageSalons = findViewById(R.id.buttonManageSalons)
        buttonImportJson = findViewById(R.id.buttonImportJson)
        buttonExportJson = findViewById(R.id.buttonExportJson)

    }

    private fun setupButtonListeners() {
        fabShowCredits.setOnClickListener {
            Log.d(TAG, "FAB Créditos presionado.") // Mensaje para Logcat, usando tu TAG
            mostrarDialogoCreditos()               // Llama a la función que muestra el diálogo
        }
        fabAddSalon.setOnClickListener {
            Log.d(TAG, "fabAddSalon presionado - Listener funcionando.")
            mostrarDialogoNombreSalon()
        }

        buttonRefreshLocation.setOnClickListener {
            Log.d(TAG, "buttonRefreshLocation presionado.")
            textViewNearbySalon.text = "Salón más cercano: Analizando..."
            esEscaneoParaLocalizacion = true
            verificarPermisosYComenzarEscaneo(nombreSalon = "")
        }

        buttonManageSalons.setOnClickListener {
            Log.d(TAG, "Botón Administrar Salones presionado.")
            mostrarDialogoAdministrarSalones()
        }
        buttonExportJson.setOnClickListener { // Configuración del listener
            Log.d(TAG, "Botón Exportar JSON presionado.")
            exportarDatosSalones()
        }

        buttonImportJson.setOnClickListener {
            Log.d(TAG, "Botón Importar JSON presionado.")
            seleccionarArchivoParaImportar()
        }


    }

    // --- Lógica de Mapeo ---


    private fun mostrarDialogoNombreSalon() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Mapear Salón")
        val input = EditText(this)
        input.hint = "Nombre del Salón (ej: 301B)"
        builder.setView(input)

        builder.setPositiveButton("Continuar") { dialog, _ ->
            val nombreIngresado = input.text.toString().trim()
            if (nombreIngresado.isNotEmpty()) {
                val salonExistente = salonesMapeados.find { it.nombreSalon.equals(nombreIngresado, ignoreCase = true) }
                reemplazarMuestrasDelSalonActual = false // Resetear el flag por defecto

                if (salonExistente != null) {
                    confirmarAnadirOReemplazarMuestras(salonExistente) // Cambiamos el nombre de la función
                } else {
                    nombreSalonTemporal = nombreIngresado
                    esEscaneoParaLocalizacion = false
                    verificarPermisosYComenzarEscaneo(nombreIngresado)
                }
            } else {
                Toast.makeText(this, "El nombre del salón no puede estar vacío.", Toast.LENGTH_SHORT).show()
            }

        }
        builder.setNegativeButton("Cancelar") { dialog, _ ->
            dialog.cancel()
            nombreSalonTemporal = null
            reemplazarMuestrasDelSalonActual = false
        }
        builder.show()
    }

    // NOMBRE CAMBIADO Y LÓGICA ACTUALIZADA
    private fun confirmarAnadirOReemplazarMuestras(salon: SalonInfo) {
        val message = "El salón '${salon.nombreSalon}' ya tiene ${salon.muestrasDeHuellas.size} muestra(s).\n\n" +
                "¿Qué deseas hacer?"

        AlertDialog.Builder(this)
            .setTitle("Salón Existente")
            .setMessage(message)
            .setPositiveButton("Añadir Nueva Muestra") { dialog, _ ->
                nombreSalonTemporal = salon.nombreSalon
                esEscaneoParaLocalizacion = false
                reemplazarMuestrasDelSalonActual = false
                verificarPermisosYComenzarEscaneo(salon.nombreSalon)
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                nombreSalonTemporal = null
                reemplazarMuestrasDelSalonActual = false
                dialog.dismiss()
            }
            .setNeutralButton("Reemplazar Todas") { dialog, _ ->
                nombreSalonTemporal = salon.nombreSalon
                esEscaneoParaLocalizacion = false
                reemplazarMuestrasDelSalonActual = true //
                verificarPermisosYComenzarEscaneo(salon.nombreSalon)
                dialog.dismiss()
            }
            .show()
    }



    private fun verificarPermisosYComenzarEscaneo(nombreSalon: String) {
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Por favor, activa el Wi-Fi.", Toast.LENGTH_LONG).show()
            if (!esEscaneoParaLocalizacion) nombreSalonTemporal = null
            esEscaneoParaLocalizacion = false
            return
        }

        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                Log.i(TAG_PERMISSIONS, "Permiso ACCESS_FINE_LOCATION ya concedido.")
                if (esEscaneoParaLocalizacion) {
                    iniciarEscaneoWifiParaLocalizacion()
                } else {
                    iniciarEscaneoWifiParaSalon(nombreSalon)
                }
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                AlertDialog.Builder(this)
                    .setTitle("Permiso de Ubicación Necesario")
                    .setMessage("Esta aplicación necesita acceso a la ubicación para escanear redes Wi-Fi y así poder identificar salones.")
                    .setPositiveButton("Entendido") { _, _ ->
                        requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    .setNegativeButton("Cancelar") { dialog, _ ->
                        dialog.dismiss()
                        if (!esEscaneoParaLocalizacion) nombreSalonTemporal = null
                        esEscaneoParaLocalizacion = false
                        Toast.makeText(this, "Permiso denegado. No se puede continuar.", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            else -> {
                requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun iniciarEscaneoWifiParaSalon(nombreSalon: String) {
        Log.d(TAG_WIFI, "Iniciando escaneo Wi-Fi para el salón: $nombreSalon")
        textViewDebugWifiInfo.text = "Escaneando Wi-Fi para: $nombreSalon..."
        realizarEscaneoWifiComun()
    }

    private fun iniciarEscaneoWifiParaLocalizacion() {
        Log.d(TAG_WIFI, "Iniciando escaneo Wi-Fi para localización.")
        textViewDebugWifiInfo.text = "Escaneando Wi-Fi para localizar..."
        realizarEscaneoWifiComun()
    }

    private fun realizarEscaneoWifiComun() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Error: Permiso de ubicación no concedido.", Toast.LENGTH_LONG).show()
            Log.e(TAG_WIFI, "Intento de escanear sin permiso.")
            if (!esEscaneoParaLocalizacion) nombreSalonTemporal = null
            esEscaneoParaLocalizacion = false
            return
        }
        try {
            val scanEncolado = wifiManager.startScan()
            if (scanEncolado) {
                Toast.makeText(this, "Escaneando Wi-Fi...", Toast.LENGTH_SHORT).show()
                Log.d(TAG_WIFI, "Solicitud de escaneo Wi-Fi encolada exitosamente.")
            } else {
                Log.w(TAG_WIFI, "wifiManager.startScan() devolvió false. El sistema podría estar ocupado/throttling.")
                Toast.makeText(this, "No se pudo iniciar el escaneo Wi-Fi. Intenta de nuevo.", Toast.LENGTH_LONG).show()
                textViewDebugWifiInfo.append("\nFallo al iniciar solicitud de escaneo.")
                if (!esEscaneoParaLocalizacion) nombreSalonTemporal = null
                esEscaneoParaLocalizacion = false
            }
        } catch (e: Exception) {
            Log.e(TAG_WIFI, "Excepción al iniciar escaneo: ${e.message}", e)
            Toast.makeText(this, "Error al iniciar escaneo Wi-Fi.", Toast.LENGTH_LONG).show()
            if (!esEscaneoParaLocalizacion) nombreSalonTemporal = null
            esEscaneoParaLocalizacion = false
        }
    }

    private fun procesarResultadosEscaneoParaMapeo() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG_WIFI, "Procesando resultados de mapeo sin permiso. Abortando.")
            nombreSalonTemporal = null
            reemplazarMuestrasDelSalonActual = false
            return
        }

        val scanResults: List<ScanResult>
        try {
            scanResults = wifiManager.scanResults
        } catch (se: SecurityException) {
            Log.e(TAG_WIFI, "SecurityException al obtener scanResults para mapeo: ${se.message}")
            Toast.makeText(
                this,
                "Error de seguridad al obtener resultados Wi-Fi.",
                Toast.LENGTH_SHORT
            ).show()
            nombreSalonTemporal = null
            reemplazarMuestrasDelSalonActual = false
            return
        }

        val currentFingerprints = mutableListOf<WifiFingerprint>()
        val debugInfo =
            StringBuilder("Resultados para mapeo de ${nombreSalonTemporal ?: "[Salón no especificado]"}:\n")

        scanResults.forEach { result ->
            if (!result.BSSID.isNullOrEmpty() && result.level >= MIN_RSSI_THRESHOLD) {
                currentFingerprints.add(WifiFingerprint(result.BSSID, result.level))
                debugInfo.append("SSID: ${result.SSID ?: "N/A"}, BSSID: ${result.BSSID}, RSSI: ${result.level} dBm (GUARDADO)\n")
            } else if (!result.BSSID.isNullOrEmpty()) {
                debugInfo.append("SSID: ${result.SSID ?: "N/A"}, BSSID: ${result.BSSID}, RSSI: ${result.level} dBm (IGNORADO - Débil)\n")
            }
        }
        textViewDebugWifiInfo.text = debugInfo.toString()

        // Guardar nombreSalonTemporal antes de que se limpie en el bloque 'let' si no se usa 'nombre'
        val nombreDelSalonActual = nombreSalonTemporal

        nombreDelSalonActual?.let { nombre ->
            if (currentFingerprints.isNotEmpty()) {
                val existingSalonIndex = salonesMapeados.indexOfFirst {
                    it.nombreSalon.equals(
                        nombre,
                        ignoreCase = true
                    )
                }

                var operacionCompletadaConExito = false

                if (existingSalonIndex != -1) {
                    // Salón existente
                    val salonExistente = salonesMapeados[existingSalonIndex]
                    if (reemplazarMuestrasDelSalonActual) {
                        salonExistente.muestrasDeHuellas.clear()
                        Log.i(
                            TAG,
                            "Muestras anteriores de '${salonExistente.nombreSalon}' borradas para reemplazo."
                        )
                    }
                    salonExistente.muestrasDeHuellas.add(ArrayList(currentFingerprints))
                    salonExistente.timestampUltimaMuestra = System.currentTimeMillis()
                    operacionCompletadaConExito = true

                    if (reemplazarMuestrasDelSalonActual) {
                        Log.i(
                            TAG,
                            "Muestras de '${salonExistente.nombreSalon}' reemplazadas. Total muestras: ${salonExistente.muestrasDeHuellas.size}"
                        )
                    } else {
                        Log.i(
                            TAG,
                            "Nueva muestra añadida a '${salonExistente.nombreSalon}'. Total muestras: ${salonExistente.muestrasDeHuellas.size}"
                        )
                    }
                } else {
                    // Nuevo salón
                    val primeraMuestra: List<WifiFingerprint> = ArrayList(currentFingerprints)
                    val nuevaListaDeMuestras: MutableList<List<WifiFingerprint>> =
                        mutableListOf(primeraMuestra)
                    val nuevoSalon =
                        SalonInfo(nombre, nuevaListaDeMuestras, System.currentTimeMillis())
                    salonesMapeados.add(nuevoSalon)
                    operacionCompletadaConExito = true
                    Log.i(TAG, "Salón '${nuevoSalon.nombreSalon}' mapeado con 1 muestra.")

                }

                if (operacionCompletadaConExito) {
                    guardarSalonesEnJson()
                    // Mostrar diálogo para tomar otra muestra o finalizar, usando 'nombre'
                    preguntarSiMapearOtraMuestra(nombre)
                }

            } else {
                Toast.makeText(
                    this,
                    "No se detectaron redes Wi-Fi válidas para el salón '$nombre'. No se añadió muestra.",
                    Toast.LENGTH_LONG
                ).show()
            }
            // Resetear flags globales después de la operación
            nombreSalonTemporal = null // Limpiar esto aquí, ya que nombreDelSalonActual se usó
            reemplazarMuestrasDelSalonActual = false
        }
    }

    private fun preguntarSiMapearOtraMuestra(nombreSalonMapeado: String) {
        val salonInfo = salonesMapeados.find { it.nombreSalon.equals(nombreSalonMapeado, ignoreCase = true) }
        val mensaje = if (salonInfo != null) {
            "Muestra para '${salonInfo.nombreSalon}' guardada (total ${salonInfo.muestrasDeHuellas.size} muestras).\n\n¿Deseas tomar otra muestra para este mismo salón?"
        } else {
            // Esto no debería pasar si la lógica anterior es correcta
            "Muestra guardada. ¿Deseas tomar otra muestra para este salón?"
        }

        AlertDialog.Builder(this)
            .setTitle("Mapeo Continuo")
            .setMessage(mensaje)
            .setPositiveButton("Sí, otra muestra") { _, _ ->
                // Preparamos para otro escaneo del mismo salón
                nombreSalonTemporal = nombreSalonMapeado // Re-establecer el nombre del salón
                esEscaneoParaLocalizacion = false
                reemplazarMuestrasDelSalonActual = false // Para la siguiente muestra, por defecto se añade, no se reemplaza todo
                verificarPermisosYComenzarEscaneo(nombreSalonMapeado)
            }
            .setNegativeButton("No, terminar") { dialog, _ ->
                Toast.makeText(this, "Mapeo para '$nombreSalonMapeado' finalizado.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setCancelable(false) // Evitar que se cierre el diálogo presionando fuera
            .show()
    }


    // --- Lógica de Localización ---
    data class SalonConDistancia(val salon: SalonInfo, val distancia: Double) // Clase auxiliar

    private fun procesarResultadosParaLocalizacion() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG_WIFI, "Procesando resultados de localización sin permiso. Abortando.")
            textViewNearbySalon.text = "Salón más cercano: Permiso de ubicación denegado."
            return
        }

        val scanResults: List<ScanResult>
        try {
            scanResults = wifiManager.scanResults
        } catch (se: SecurityException) {
            Log.e(TAG_WIFI, "SecurityException al obtener scanResults para localización: ${se.message}")
            Toast.makeText(this, "Error de seguridad al obtener resultados Wi-Fi.", Toast.LENGTH_SHORT).show()
            textViewNearbySalon.text = "Salón más cercano: Error de seguridad."
            return
        }

        val currentScanFingerprints = mutableListOf<WifiFingerprint>()
        val debugInfo = StringBuilder("Escaneo actual para localización (${scanResults.size} APs detectados):\n")
        scanResults.forEach { result ->
            if (!result.BSSID.isNullOrEmpty() && result.level >= MIN_RSSI_THRESHOLD) { // <-- ¡NUEVA CONDICIÓN AQUÍ!
                currentScanFingerprints.add(WifiFingerprint(result.BSSID, result.level))
                debugInfo.append("  ${result.BSSID} : ${result.level}dBm (${result.SSID ?: "N/A"}) (CONSIDERADO)\n")
            } else if (!result.BSSID.isNullOrEmpty()){
                debugInfo.append("  ${result.BSSID} : ${result.level}dBm (${result.SSID ?: "N/A"}) (IGNORADO - Débil)\n")
            }
        }
        debugInfo.append("APs válidos en escaneo actual para localización: ${currentScanFingerprints.size}\n")



        if (currentScanFingerprints.isEmpty()) {
            textViewNearbySalon.text = "Salón más cercano: No se detectaron redes Wi-Fi suficientemente fuertes."
            Toast.makeText(this, "No se detectaron redes fuertes para localizar.", Toast.LENGTH_SHORT).show()
            textViewDebugWifiInfo.text = debugInfo.toString() // Mostrar los APs ignorados
            return
        }
        if (salonesMapeados.isEmpty()) {
            textViewNearbySalon.text = "Salón más cercano: Aún no hay salones mapeados."
            Toast.makeText(this, "Primero mapea algunos salones.", Toast.LENGTH_SHORT).show()
            textViewDebugWifiInfo.text = debugInfo.toString() + "\nLocalización: No hay salones mapeados para comparar."
            return
        }

        val salonesConMejorDistancia = mutableListOf<SalonConDistancia>()
        debugInfo.append("\nComparando con salones guardados (k=$K_NEIGHBORS):\n")

        salonesMapeados.forEach { salonGuardado ->
            if (salonGuardado.muestrasDeHuellas.isEmpty()) {
                debugInfo.append("Salón ${salonGuardado.nombreSalon} no tiene muestras guardadas.\n")
                return@forEach
            }
            var mejorDistanciaParaEsteSalon = Double.MAX_VALUE
            var indiceMejorMuestra = -1
            salonGuardado.muestrasDeHuellas.forEachIndexed { index, muestraHuella ->
                // ¡Importante! La 'muestraHuella' ya fue filtrada al guardarse.
                val distanciaAMuestra = calcularDistanciaEuclidiana(currentScanFingerprints, muestraHuella)
                if (distanciaAMuestra < mejorDistanciaParaEsteSalon) {
                    mejorDistanciaParaEsteSalon = distanciaAMuestra
                    indiceMejorMuestra = index
                }
            }
            if (indiceMejorMuestra != -1) {
                salonesConMejorDistancia.add(SalonConDistancia(salonGuardado, mejorDistanciaParaEsteSalon))
                debugInfo.append("Mejor Distancia a ${salonGuardado.nombreSalon} (muestra ${indiceMejorMuestra + 1}/${salonGuardado.muestrasDeHuellas.size}): ${"%.2f".format(mejorDistanciaParaEsteSalon)}\n")
            } else {
                debugInfo.append("No se pudo calcular distancia para ${salonGuardado.nombreSalon}.\n")
            }
        }


        if (salonesConMejorDistancia.isEmpty()) {
            textViewNearbySalon.text = "Salón más cercano: No hay salones con datos para comparar."
            textViewDebugWifiInfo.text = debugInfo.toString()
            return
        }

        salonesConMejorDistancia.sortBy { it.distancia }
        val vecinosMasCercanos = salonesConMejorDistancia.take(K_NEIGHBORS)

        if (vecinosMasCercanos.isEmpty()) {
            textViewNearbySalon.text = "Salón más cercano: No se pudo determinar."
            textViewDebugWifiInfo.text = debugInfo.toString() + "\nNo se encontraron vecinos."
            return
        }

        val mejorVecino = vecinosMasCercanos.first()
        var salonPredichoNombre = "Desconocido"
        var confianza = "Baja"
        var mensajeDetallado: String
        val prefixText = "Salón más cercano: "
        val salonText = salonPredichoNombre // El nombre del salón que ya determinaste
        val confidenceText = "\n(Confianza: $confianza)"
        val spannableStringBuilder = SpannableStringBuilder()

        // Añadir el prefijo con estilo normal
        spannableStringBuilder.append(prefixText)

        // Añadir el nombre del salón con color vivo y negrita
        val startIndexOfSalon = spannableStringBuilder.length
        spannableStringBuilder.append(salonText)
        val endIndexOfSalon = spannableStringBuilder.length
        //val vibrantColor = ContextCompat.getColor(this, R.color.color_salon_destacado_vivo)
        val vibrantColor = Color.parseColor("#FF03DAC5")
        if (salonPredichoNombre != "Desconocido" && salonPredichoNombre != "Ubicación Desconocida" && !salonPredichoNombre.contains("Error")) {
            spannableStringBuilder.setSpan(
                ForegroundColorSpan(vibrantColor),
                startIndexOfSalon,
                endIndexOfSalon,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannableStringBuilder.setSpan(
                StyleSpan(Typeface.BOLD), // Poner en negrita
                startIndexOfSalon,
                endIndexOfSalon,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        spannableStringBuilder.append(confidenceText)
        textViewNearbySalon.text = spannableStringBuilder

        if (mejorVecino.distancia > MAX_ACCEPTABLE_DISTANCE_FOR_LOCALIZATION) {
            salonPredichoNombre = "Ubicación Desconocida"
            confianza = "Muy Baja (Distancia > ${"%.0f".format(MAX_ACCEPTABLE_DISTANCE_FOR_LOCALIZATION)})"
            mensajeDetallado = "Las señales Wi-Fi no coinciden lo suficiente con ningún salón mapeado."
        } else {
            val conteoSalones = vecinosMasCercanos.groupingBy { it.salon.nombreSalon }.eachCount()
            val salonMasFrecuenteEntry = conteoSalones.maxByOrNull { it.value }

            if (salonMasFrecuenteEntry != null) {
                val nombreMasFrecuente = salonMasFrecuenteEntry.key
                val votosMasFrecuente = salonMasFrecuenteEntry.value
                val minVotosParaAltaConfianza = if (vecinosMasCercanos.size < K_NEIGHBORS && vecinosMasCercanos.isNotEmpty()) vecinosMasCercanos.size else MIN_MAJORITY_FOR_HIGH_CONFIDENCE

                if (votosMasFrecuente >= minVotosParaAltaConfianza && vecinosMasCercanos.size > 0) {
                    salonPredichoNombre = nombreMasFrecuente
                    confianza = "Alta (${votosMasFrecuente}/${vecinosMasCercanos.size} votos)"
                } else {
                    salonPredichoNombre = mejorVecino.salon.nombreSalon
                    confianza = if (vecinosMasCercanos.size == 1) "Media (Único cercano)" else "Media (Mejor opción)"
                }
                mensajeDetallado = "Cerca de: $salonPredichoNombre (Dist: ${"%.2f".format(mejorVecino.distancia)})"
            } else {
                salonPredichoNombre = "Error procesando vecinos"
                confianza = "Error"
                mensajeDetallado = "Error interno determinando salón."
            }
        }


        debugInfo.append("\nDecisión: $salonPredichoNombre, Confianza: $confianza")
        textViewNearbySalon.text = "Salón más cercano: $salonPredichoNombre\n(Confianza: $confianza)"
        Toast.makeText(this, mensajeDetallado, Toast.LENGTH_LONG).show()
        textViewDebugWifiInfo.text = debugInfo.toString()

    }


    private fun calcularDistanciaEuclidiana(scanActual: List<WifiFingerprint>, huellaGuardada: List<WifiFingerprint>): Double {
        var sumaDiferenciasCuadradas = 0.0
        val mapActual = scanActual.associateBy({ it.bssid }, { it.rssi })
        val mapGuardado = huellaGuardada.associateBy({ it.bssid }, { it.rssi })
        val todosLosBssidsUnicos = (mapActual.keys + mapGuardado.keys).toSet()

        if (todosLosBssidsUnicos.isEmpty()) {
            return Double.MAX_VALUE
        }

        todosLosBssidsUnicos.forEach { bssid ->
            val rssiActual = mapActual[bssid] ?: DEFAULT_RSSI_FOR_MISSING_AP
            val rssiGuardado = mapGuardado[bssid] ?: DEFAULT_RSSI_FOR_MISSING_AP
            val diferencia = (rssiActual - rssiGuardado).toDouble()
            sumaDiferenciasCuadradas += diferencia * diferencia
        }
        return sqrt(sumaDiferenciasCuadradas)
    }

    // --- Persistencia de Datos (JSON) ---
    private fun guardarSalonesEnJson() {
        val gson = Gson()
        val listaAGuardar = ArrayList(salonesMapeados)
        val jsonString = gson.toJson(listaAGuardar)
        try {
            val file = File(filesDir, SALONES_FILENAME)
            FileWriter(file).use { writer ->
                writer.write(jsonString)
            }
            Log.i(TAG, "Salones guardados en JSON. Total: ${listaAGuardar.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error al guardar salones en JSON: ${e.message}", e)
            // Considerar un Toast si el guardado falla, para que el usuario sepa.
            // Toast.makeText(this, "Error al guardar datos de salones.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cargarSalonesDesdeJson() {
        val file = File(filesDir, SALONES_FILENAME)
        if (file.exists() && file.length() > 0) {
            try {
                FileReader(file).use { reader ->
                    val gson = Gson()
                    val salonListType = object : TypeToken<ArrayList<SalonInfo>>() {}.type
                    val cargados: ArrayList<SalonInfo>? = gson.fromJson(reader, salonListType)

                    if (cargados != null) {
                        salonesMapeados.clear()
                        salonesMapeados.addAll(cargados)
                        Log.i(TAG, "Salones cargados desde JSON. Total: ${salonesMapeados.size}")
                        // Opcional: Actualizar UI si es necesario para mostrar que se cargaron datos.
                    } else {
                        Log.w(TAG, "El archivo JSON devolvió null al parsear, iniciando lista vacía.")
                        salonesMapeados.clear()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al cargar salones desde JSON: ${e.message}", e)
                Toast.makeText(this, "Error al cargar datos. Se iniciará vacío.", Toast.LENGTH_LONG).show()
                salonesMapeados.clear()
            }
        } else {
            Log.i(TAG, "No se encontró el archivo JSON de salones o está vacío.")
            salonesMapeados.clear()
        }
    }

    // --- Administración de Salones ---
    private fun mostrarDialogoAdministrarSalones() {
        if (salonesMapeados.isEmpty()) {
            Toast.makeText(this, "Aún no hay salones mapeados.", Toast.LENGTH_SHORT).show()
            return
        }

        val nombresSalonesConConteo = salonesMapeados.map {
            "${it.nombreSalon} (${it.muestrasDeHuellas.size} muestra${if (it.muestrasDeHuellas.size != 1) "s" else ""})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Administrar Salones")
            .setItems(nombresSalonesConConteo) { dialog, which ->
                // 'which' es el índice del salón seleccionado
                val salonSeleccionado = salonesMapeados[which]
                mostrarOpcionesParaSalon(salonSeleccionado, which) // <--- NUEVA LLAMADA
            }
            .setNegativeButton("Volver", null) // Cambiado de "Cancelar" a "Volver"
            .show()
    }

    // En MainActivity.kt

    private fun mostrarOpcionesParaSalon(salon: SalonInfo, indexEnLista: Int) {
        val opciones = arrayOf(
            "Ver detalles de muestras",                 // Opción 0
            "Añadir Nueva Muestra a este Salón",    // Opción 1 (NUEVA)
            "Borrar TODAS las muestras de este salón", // Opción 2 (antes era 1)
            "Borrar SALÓN COMPLETO",                // Opción 3 (antes era 2)
            "Cancelar"                             // Opción 4 (antes era 3)
        )

        AlertDialog.Builder(this)
            .setTitle("Opciones para: ${salon.nombreSalon}")
            .setItems(opciones) { dialog, which ->
                when (which) {
                    0 -> { // Ver detalles de muestras
                        verDetallesMuestrasSalon(salon)
                    }
                    1 -> { // Añadir Nueva Muestra a este Salón
                        Log.d(TAG, "Opción: Añadir nueva muestra a '${salon.nombreSalon}'")
                        nombreSalonTemporal = salon.nombreSalon // Establecer el nombre para el escaneo
                        esEscaneoParaLocalizacion = false
                        reemplazarMuestrasDelSalonActual = false // Queremos añadir, no reemplazar
                        verificarPermisosYComenzarEscaneo(salon.nombreSalon)

                    }
                    2 -> { // Borrar todas las muestras de este salón
                        confirmarBorradoMuestrasDeSalon(salon)
                    }
                    3 -> { // Borrar salón completo
                        confirmarBorradoSalonCompleto(salon, indexEnLista)
                    }
                    4 -> { // Cancelar
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    private fun verDetallesMuestrasSalon(salon: SalonInfo) {
        if (salon.muestrasDeHuellas.isEmpty()) {
            Toast.makeText(this, "El salón '${salon.nombreSalon}' no tiene muestras de Wi-Fi actualmente.", Toast.LENGTH_SHORT).show()
            return
        }

        val etiquetasMuestras = salon.muestrasDeHuellas.mapIndexed { index, muestra ->
            // Podríamos añadir un timestamp por muestra si lo guardáramos individualmente,
            // pero por ahora nos basamos en el timestamp general del salón y el índice.
            "Muestra ${index + 1} (${muestra.size} APs)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Muestras para: ${salon.nombreSalon}")
            .setItems(etiquetasMuestras) { _, which -> // which es el índice de la muestra seleccionada
                val muestraSeleccionada = salon.muestrasDeHuellas[which]
                val indiceMuestra = which // Guardamos el índice para posible borrado
                mostrarOpcionesParaMuestraIndividual(salon, muestraSeleccionada, indiceMuestra) // NUEVA LLAMADA
            }
            .setNegativeButton("Volver", null)
            .show()
    }
// En MainActivity.kt

    private fun confirmarBorradoMuestraIndividual(salon: SalonInfo, indiceMuestraABorrar: Int) {
        if (indiceMuestraABorrar < 0 || indiceMuestraABorrar >= salon.muestrasDeHuellas.size) {
            Log.e(TAG, "Índice de muestra a borrar inválido.")
            Toast.makeText(this, "Error al seleccionar la muestra.", Toast.LENGTH_SHORT).show()
            return
        }

        val numeroMuestraVisual = indiceMuestraABorrar + 1
        AlertDialog.Builder(this)
            .setTitle("Confirmar Borrado de Muestra")
            .setMessage("¿Estás seguro de que quieres borrar la Muestra $numeroMuestraVisual del salón '${salon.nombreSalon}'?")
            .setPositiveButton("Sí, Borrar Muestra") { _, _ ->
                salon.muestrasDeHuellas.removeAt(indiceMuestraABorrar)
                salon.timestampUltimaMuestra = System.currentTimeMillis() // Actualizar timestamp general del salón
                guardarSalonesEnJson() // Guardar el cambio

                Toast.makeText(this, "Muestra $numeroMuestraVisual de '${salon.nombreSalon}' borrada.", Toast.LENGTH_LONG).show()
                Log.i(TAG, "Muestra $numeroMuestraVisual de '${salon.nombreSalon}' borrada. Muestras restantes: ${salon.muestrasDeHuellas.size}")

                // Opcional: Si después de borrar no quedan muestras, podrías notificar o incluso preguntar si se quiere borrar el salón.
                if (salon.muestrasDeHuellas.isEmpty()) {
                    Toast.makeText(this, "El salón '${salon.nombreSalon}' ya no tiene muestras.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarOpcionesParaMuestraIndividual(salonOriginal: SalonInfo, muestra: List<WifiFingerprint>, indiceMuestra: Int) {
        val opciones = arrayOf(
            "Ver APs de esta Muestra",      // Opción 0
            "Borrar ESTA Muestra",         // Opción 1
            "Cancelar"                     // Opción 2
        )

        AlertDialog.Builder(this)
            .setTitle("Opciones para Muestra ${indiceMuestra + 1} de '${salonOriginal.nombreSalon}'")
            .setItems(opciones) { dialog, which ->
                when (which) {
                    0 -> { // Ver APs de esta Muestra
                        // Reutilizamos la función que ya teníamos, pero pasamos el número de muestra y nombre del salón
                        mostrarDetalleDeUnaMuestra(muestra, indiceMuestra + 1, salonOriginal.nombreSalon)
                    }
                    1 -> { // Borrar ESTA Muestra
                        confirmarBorradoMuestraIndividual(salonOriginal, indiceMuestra)
                    }
                    2 -> { // Cancelar
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    private fun mostrarDetalleDeUnaMuestra(muestra: List<WifiFingerprint>, numeroMuestra: Int, salonNombre: String) {
        if (muestra.isEmpty()) {
            Toast.makeText(this, "Esta muestra está vacía.", Toast.LENGTH_SHORT).show()
            return
        }

        val detallesString = StringBuilder()
        detallesString.append("Detalles de Muestra $numeroMuestra para Salón '$salonNombre':\n\n")
        muestra.forEach { fingerprint ->
            detallesString.append("BSSID: ${fingerprint.bssid}\n")
            detallesString.append("  RSSI: ${fingerprint.rssi} dBm\n")
            detallesString.append("--------------------\n")
        }

        // Usar un ScrollView dentro del AlertDialog si la lista de APs es muy larga
        val scrollView = ScrollView(this)
        val textViewDetalles = TextView(this).apply {
            text = detallesString.toString()
            setPadding(40, 40, 40, 40) // Añadir un poco de padding
            setTextIsSelectable(true) // Permitir copiar el texto
        }
        scrollView.addView(textViewDetalles)

        AlertDialog.Builder(this)
            .setTitle("Detalle Muestra $numeroMuestra ($salonNombre)")
            .setView(scrollView) // Añadir el ScrollView con el TextView al diálogo
            .setPositiveButton("Aceptar", null)
            .show()
    }

    private fun confirmarBorradoMuestrasDeSalon(salon: SalonInfo) {
        if (salon.muestrasDeHuellas.isEmpty()){
            Toast.makeText(this, "El salón '${salon.nombreSalon}' ya no tiene muestras.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Borrar Muestras")
            .setMessage("¿Estás seguro de que quieres borrar TODAS las ${salon.muestrasDeHuellas.size} muestras de Wi-Fi del salón '${salon.nombreSalon}'? El salón permanecerá en la lista, pero sin huellas.")
            .setPositiveButton("Sí, Borrar Muestras") { _, _ ->
                salon.muestrasDeHuellas.clear() // Borra todas las muestras de la lista
                salon.timestampUltimaMuestra = System.currentTimeMillis() // Actualizar timestamp
                guardarSalonesEnJson() // Guardar el cambio
                Toast.makeText(this, "Todas las muestras de '${salon.nombreSalon}' han sido borradas.", Toast.LENGTH_LONG).show()
                Log.i(TAG, "Todas las muestras de '${salon.nombreSalon}' borradas. El salón permanece.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarBorradoSalonCompleto(salon: SalonInfo, indexEnLista: Int) {
        val mensaje = if (salon.muestrasDeHuellas.isNotEmpty()) {
            "¿Estás seguro de que quieres borrar el salón COMPLETO '${salon.nombreSalon}' (con todas sus ${salon.muestrasDeHuellas.size} muestras)? Esta acción no se puede deshacer."
        } else {
            "¿Estás seguro de que quieres borrar el salón COMPLETO '${salon.nombreSalon}' (actualmente no tiene muestras)? Esta acción no se puede deshacer."
        }

        AlertDialog.Builder(this)
            .setTitle("Confirmar Borrado de Salón")
            .setMessage(mensaje) // Mensaje actualizado
            .setPositiveButton("Borrar Salón") { _, _ ->
                if (indexEnLista >= 0 && indexEnLista < salonesMapeados.size) {
                    val salonBorrado = salonesMapeados.removeAt(indexEnLista)
                    guardarSalonesEnJson()
                    Toast.makeText(this, "Salón '${salonBorrado.nombreSalon}' borrado permanentemente.", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "Salón '${salonBorrado.nombreSalon}' borrado. Salones restantes: ${salonesMapeados.size}")
                } else {
                    Log.e(TAG, "Índice de borrado inválido: $indexEnLista. Tamaño lista: ${salonesMapeados.size}")
                    Toast.makeText(this, "Error al borrar el salón.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- Diálogo de Créditos ---
    private fun mostrarDialogoCreditos() {
        AlertDialog.Builder(this)
            .setTitle("Créditos")
            .setMessage("Programa creado para la materia de Machine Learning 802 por Hernan Yessid Murcia Salinas y Carlos Felipe Gomez Plazas.")
            .setPositiveButton("Aceptar", null) // "null" como listener simplemente cierra el diálogo
            .show()
    }
    private fun exportarDatosSalones() {
        if (salonesMapeados.isEmpty()) {
            Toast.makeText(this, "No hay datos de salones para exportar.", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Intento de exportar con lista de salones vacía.") // Añade este log
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json" // Tipo MIME para archivos JSON
            putExtra(Intent.EXTRA_TITLE, SALONES_DATA_FILENAME_FOR_EXPORT) // Nombre de archivo sugerido
        }
        try {
            startActivityForResult(intent, CREATE_FILE_REQUEST_CODE)
            Log.d(TAG, "Intent ACTION_CREATE_DOCUMENT lanzado para exportar.") // Añade este log
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo iniciar la exportación: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Error al iniciar ACTION_CREATE_DOCUMENT para exportar", e)
        }
    }
    @Deprecated("Deprecated in Java") // Para usar el antiguo onActivityResult
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == AppCompatActivity.RESULT_OK) {
            when (requestCode) {
                CREATE_FILE_REQUEST_CODE -> { // Resultado de la exportación
                    data?.data?.let { uri ->
                        try {
                            contentResolver.openOutputStream(uri)?.use { outputStream ->
                                val gson = Gson()
                                val jsonString = gson.toJson(salonesMapeados) // Convertir la lista actual a JSON
                                outputStream.write(jsonString.toByteArray())
                                Toast.makeText(this, "Datos exportados exitosamente a ${uri.lastPathSegment}", Toast.LENGTH_LONG).show()
                                Log.i(TAG, "Datos de salones exportados a: ${uri.path}")
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, "Error al exportar datos: ${e.message}", Toast.LENGTH_SHORT).show()
                            Log.e(TAG, "Error durante la exportación de datos", e)
                        }
                    }
                }
                PICK_FILE_REQUEST_CODE -> { // Resultado de la importación (lo implementaremos después)
                    data?.data?.let { uri ->
                        importarDatosDesdeUri(uri)
                    }
                }
            }
        } else {
            if (requestCode == CREATE_FILE_REQUEST_CODE || requestCode == PICK_FILE_REQUEST_CODE) {
                Log.w(TAG, "Operación de archivo cancelada por el usuario.")
                // Toast.makeText(this, "Operación de archivo cancelada.", Toast.LENGTH_SHORT).show() // Opcional
            }
        }
    }
    private fun seleccionarArchivoParaImportar() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json" // Solo mostrar archivos JSON
        }
        try {
            startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo iniciar la selección de archivo: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Error al iniciar ACTION_OPEN_DOCUMENT para importar", e)
        }
    }

    private fun importarDatosDesdeUri(uri: android.net.Uri) {
        Log.d(TAG, "Intentando importar datos desde URI: $uri")
        try {
            // Usar contentResolver para obtener un InputStream a partir de la Uri
            contentResolver.openInputStream(uri)?.use { inputStream ->
                // Usar InputStreamReader para leer el contenido como texto (especificando UTF-8 es buena práctica)
                InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
                    val gson = Gson()
                    // Definir el tipo de la lista que esperamos leer del JSON
                    val salonListType = object : TypeToken<ArrayList<SalonInfo>>() {}.type

                    // Parsear el JSON a una lista de SalonInfo
                    val salonesImportados: ArrayList<SalonInfo>? = try {
                        gson.fromJson(reader, salonListType)
                    } catch (e: com.google.gson.JsonSyntaxException) {
                        Log.e(TAG, "Error de sintaxis JSON al importar: ${e.message}")
                        Toast.makeText(this, "Error: El archivo seleccionado no tiene un formato JSON válido.", Toast.LENGTH_LONG).show()
                        null
                    } catch (e: Exception) {
                        Log.e(TAG, "Error inesperado al parsear JSON: ${e.message}", e)
                        Toast.makeText(this, "Error al leer el contenido del archivo.", Toast.LENGTH_LONG).show()
                        null
                    }

                    if (salonesImportados != null) {
                        if (salonesImportados.isNotEmpty()) {
                            // Preguntar al usuario si desea reemplazar los datos existentes
                            AlertDialog.Builder(this)
                                .setTitle("Importar Datos de Salones")
                                .setMessage("Se encontraron ${salonesImportados.size} salones en el archivo. ¿Deseas reemplazar todos los salones mapeados actualmente con estos nuevos datos?")
                                .setPositiveButton("Reemplazar") { _, _ ->
                                    salonesMapeados.clear()
                                    salonesMapeados.addAll(salonesImportados)
                                    guardarSalonesEnJson() // Guardar los datos importados en el archivo interno
                                    Toast.makeText(this, "${salonesImportados.size} salones importados y guardados exitosamente.", Toast.LENGTH_LONG).show()
                                    Log.i(TAG, "${salonesImportados.size} salones importados. Datos actuales reemplazados.")
                                    // Opcional: Actualizar cualquier UI que muestre la lista de salones o la ubicación actual
                                    textViewNearbySalon.text = "Salón más cercano: N/A (Datos actualizados)" // Resetear
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        } else {
                            Toast.makeText(this, "El archivo JSON seleccionado está vacío (no contiene salones).", Toast.LENGTH_LONG).show()
                            Log.w(TAG, "Archivo JSON importado válido pero sin salones.")
                        }
                    } else {
                        // El error ya se mostró si gson.fromJson devolvió null debido a una excepción de parseo
                        // o si el archivo no pudo ser leído por otra razón manejada en el catch de más abajo.
                    }
                }
            } ?: run {
                // Esto se ejecuta si contentResolver.openInputStream(uri) devuelve null
                Toast.makeText(this, "Error: No se pudo abrir el archivo seleccionado.", Toast.LENGTH_LONG).show()
                Log.e(TAG, "Error al importar: openInputStream devolvió null para la URI: $uri")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al importar datos desde el archivo: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Excepción general durante la importación de datos desde URI", e)
        }
    }
}