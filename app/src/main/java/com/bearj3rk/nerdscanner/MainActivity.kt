package com.bearj3rk.nerdscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import coil.load
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var resultPanel: LinearLayout
    private lateinit var progress: ProgressBar
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private var lastLookupAt = 0L
    private var lookupInFlight = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "Camera permission denied. Manual search still works."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        showScanner()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 240, 230))
        }
        val title = TextView(this).apply {
            text = "BearJ3rk's Nerd Scanner"
            textSize = 24f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(23, 21, 29))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scan = Button(this).apply { text = "SCAN CARD"; setOnClickListener { showScanner() } }
        val search = Button(this).apply { text = "SEARCH NAME"; setOnClickListener { showManualSearch() } }
        tabs.addView(scan, LinearLayout.LayoutParams(0, dp(52), 1f))
        tabs.addView(search, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(title)
        root.addView(tabs)
        setContentView(root)
    }

    private fun clearContent() {
        while (root.childCount > 2) root.removeViewAt(2)
        resultPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
    }

    private fun showScanner() {
        clearContent()
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        status = TextView(this).apply {
            text = "Center one card in the frame. Hold steady while its name is read."
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status)
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
        root.addView(ScrollView(this).apply { addView(resultPanel) })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun showManualSearch() {
        clearContent()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(18))
        }
        val prompt = TextView(this).apply {
            text = "Search for any Magic: The Gathering card"
            textSize = 18f
            setTextColor(Color.rgb(23, 21, 29))
        }
        val input = EditText(this).apply {
            hint = "Card name, e.g. Black Lotus"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val searchButton = Button(this).apply { text = "SEARCH SCRYFALL" }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        status = TextView(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        val runSearch = { lookupCard(input.text.toString().trim()) }
        searchButton.setOnClickListener { runSearch() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
        }
        container.addView(prompt)
        container.addView(input)
        container.addView(searchButton)
        container.addView(progress)
        container.addView(status)
        container.addView(resultPanel)
        root.addView(ScrollView(this).apply { addView(container) }, LinearLayout.LayoutParams(-1, 0, 1f))
        input.requestFocus()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                val now = System.currentTimeMillis()
                if (lookupInFlight || now - lastLookupAt < 1800) { proxy.close(); return@setAnalyzer }
                val mediaImage = proxy.image
                if (mediaImage == null) { proxy.close(); return@setAnalyzer }
                val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        val candidate = bestCardName(text.textBlocks.flatMap { it.lines }.map { it.text })
                        if (candidate != null) lookupCard(candidate, fromCamera = true)
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bestCardName(lines: List<String>): String? = lines
        .map { it.trim() }
        .filter { it.length in 3..45 && it.count(Char::isLetter) >= 3 }
        .filterNot { it.matches(Regex(".*[©™]|[0-9]{3,}.*")) }
        .firstOrNull()

    private fun lookupCard(query: String, fromCamera: Boolean = false) {
        if (query.length < 2 || lookupInFlight) return
        lookupInFlight = true
        lastLookupAt = System.currentTimeMillis()
        runOnUiThread {
            progress.visibility = View.VISIBLE
            status.text = if (fromCamera) "Recognized “$query”… checking Scryfall" else "Searching…"
        }
        val url = "https://api.scryfall.com/cards/named?fuzzy=${Uri.encode(query)}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "BearJ3rksNerdScanner/0.1 (Android)")
            .header("Accept", "application/json;q=0.9,*/*;q=0.8")
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finishLookupError("Network error: ${e.localizedMessage}")
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        val message = runCatching { JSONObject(body).optString("details") }.getOrNull()
                        finishLookupError(message ?: "No matching card found")
                    } else runCatching { JSONObject(body) }
                        .onSuccess { card -> runOnUiThread { showCard(card); finishLookup() } }
                        .onFailure { finishLookupError("Could not read Scryfall's response") }
                }
            }
        })
    }

    private fun showCard(card: JSONObject) {
        resultPanel.removeAllViews()
        val imageUris = card.optJSONObject("image_uris")
            ?: card.optJSONArray("card_faces")?.optJSONObject(0)?.optJSONObject("image_uris")
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageUris?.optString("normal")?.takeIf { it.isNotBlank() }?.let { load(it) }
        }
        val name = card.optString("name", "Unknown card")
        val setName = card.optString("set_name")
        val number = card.optString("collector_number")
        val prices = card.optJSONObject("prices")
        fun price(key: String, currency: String) = prices?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }?.let { "$currency$it" } ?: "—"
        val info = TextView(this).apply {
            text = "$name\n$setName · #$number\n\nUSD ${price("usd", "$")}   Foil ${price("usd_foil", "$")}\nEUR ${price("eur", "€")}" 
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(23, 21, 29))
            setPadding(0, dp(12), 0, dp(12))
        }
        val uri = card.optString("scryfall_uri")
        val open = Button(this).apply {
            text = "OPEN ON SCRYFALL"
            isEnabled = uri.isNotBlank()
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
        }
        resultPanel.addView(image, LinearLayout.LayoutParams(-1, dp(360)))
        resultPanel.addView(info)
        resultPanel.addView(open)
        getSharedPreferences("recent", MODE_PRIVATE).edit()
            .putString("last_card", name).putString("last_uri", uri).apply()
        status.text = "Match found. Verify the set and collector number before using the price."
    }

    private fun finishLookup() { lookupInFlight = false; progress.visibility = View.GONE }
    private fun finishLookupError(message: String) = runOnUiThread {
        finishLookup()
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
        http.dispatcher.executorService.shutdown()
    }
}
