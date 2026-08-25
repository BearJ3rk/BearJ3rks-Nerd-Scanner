package com.bearj3rk.nerdscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var scannedBitmap: Bitmap? = null

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
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom + dp(12))
            insets
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
        val cameraFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3), Color.rgb(201, 154, 69))
                    cornerRadius = dp(14).toFloat()
                }
            }, FrameLayout.LayoutParams(dp(250), dp(350), Gravity.CENTER))
            addView(TextView(this@MainActivity).apply {
                text = "SET SYMBOL + NUMBER"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(180, 23, 21, 29))
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }, FrameLayout.LayoutParams(dp(170), dp(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(34)
            })
        }
        status = TextView(this).apply {
            text = "Center one card in the frame. Hold steady while its name is read."
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(cameraFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(410)))
        root.addView(status)
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
        root.addView(ScrollView(this).apply { addView(resultPanel) }, LinearLayout.LayoutParams(-1, 0, 1f))
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
                        val lines = text.textBlocks.flatMap { it.lines }.map { it.text }
                        val candidate = bestCardName(lines)
                        if (candidate != null) {
                            scannedBitmap = previewView.bitmap
                            val hints = printingHints(lines)
                            lookupCard(candidate, hints.first, hints.second, fromCamera = true)
                        }
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

    private fun printingHints(lines: List<String>): Pair<String?, String?> {
        val joined = lines.joinToString(" ").uppercase()
        val modern = Regex("\\b([A-Z0-9]{3,6})\\s*[•·]\\s*[A-Z]{2}\\s+(\\d{1,4}[A-Z]?)\\b").find(joined)
        if (modern != null) return modern.groupValues[1] to modern.groupValues[2]
        val collector = Regex("\\b(\\d{1,4}[A-Z]?)\\s*/\\s*\\d{1,4}\\b").find(joined)?.groupValues?.get(1)
        val setCode = lines.asReversed().asSequence()
            .flatMap { Regex("\\b[A-Z0-9]{3,6}\\b").findAll(it.uppercase()).map(MatchResult::value) }
            .firstOrNull { token -> token.any(Char::isLetter) && token !in setOf("WIZARDS", "MAGIC", "THE") }
        return setCode to collector
    }

    private fun lookupCard(
        query: String,
        setCode: String? = null,
        collectorNumber: String? = null,
        fromCamera: Boolean = false
    ) {
        if (query.length < 2 || lookupInFlight) return
        lookupInFlight = true
        lastLookupAt = System.currentTimeMillis()
        runOnUiThread {
            progress.visibility = View.VISIBLE
            val printing = if (setCode != null && collectorNumber != null) " ($setCode #$collectorNumber)" else ""
            status.text = if (fromCamera) "Recognized “$query”$printing… checking Scryfall" else "Searching…"
        }
        val fuzzyUrl = "https://api.scryfall.com/cards/named?fuzzy=${Uri.encode(query)}"
        val exactUrl = if (setCode != null && collectorNumber != null) {
            "https://api.scryfall.com/cards/${Uri.encode(setCode.lowercase())}/${Uri.encode(collectorNumber)}"
        } else null
        requestCard(exactUrl ?: fuzzyUrl, if (exactUrl != null) fuzzyUrl else null)
    }

    private fun requestCard(url: String, fallbackUrl: String?) {
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
                        if (fallbackUrl != null) {
                            requestCard(fallbackUrl, null)
                        } else {
                            val message = runCatching { JSONObject(body).optString("details") }.getOrNull()
                            finishLookupError(message ?: "No matching card found")
                        }
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
        scannedBitmap?.let { bitmap ->
            resultPanel.addView(TextView(this).apply {
                text = "JUST SCANNED"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(23, 21, 29))
            })
            resultPanel.addView(ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bitmap)
            }, LinearLayout.LayoutParams(-1, dp(170)).apply { bottomMargin = dp(10) })
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
        resultPanel.addView(image, LinearLayout.LayoutParams(-1, dp(280)))
        resultPanel.addView(info)
        resultPanel.addView(open, LinearLayout.LayoutParams(-1, dp(56)).apply {
            topMargin = dp(8)
            bottomMargin = dp(24)
        })
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
