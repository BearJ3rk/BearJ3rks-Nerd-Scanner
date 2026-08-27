package com.bearj3rk.nerdscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil.load
import coil.decode.SvgDecoder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.Call
import okhttp3.Cache
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var resultPanel: LinearLayout
    private lateinit var progress: ProgressBar
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val http by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "scryfall_http_cache"), 100L * 1024L * 1024L))
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private var lastLookupAt = 0L
    private var lookupInFlight = false
    private var lookupFromCamera = false
    private var historyReplacementId: String? = null
    private val setIcons = mutableMapOf<String, String>()
    @Volatile private var pendingCardArt: Bitmap? = null

    private data class Printing(
        val id: String,
        val setCode: String,
        val setName: String,
        val collectorNumber: String,
        val artUrl: String
    )

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
        val topBar = FrameLayout(this).apply { setBackgroundColor(Color.rgb(23, 21, 29)) }
        val title = TextView(this).apply {
            text = "BearJ3rk's Nerd Scanner"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        val settings = Button(this).apply {
            text = "⚙"
            textSize = 25f
            contentDescription = "Update and settings"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showSettings() }
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scan = Button(this).apply { text = "SCAN CARD"; setOnClickListener { showScanner() } }
        val cardList = Button(this).apply { text = "MY LIST"; setOnClickListener { showCardList() } }
        val history = Button(this).apply { text = "HISTORY"; setOnClickListener { showHistory() } }
        val search = Button(this).apply { text = "SEARCH NAME"; setOnClickListener { showManualSearch() } }
        tabs.addView(scan, LinearLayout.LayoutParams(0, dp(52), 1f))
        tabs.addView(cardList, LinearLayout.LayoutParams(0, dp(52), 1f))
        tabs.addView(history, LinearLayout.LayoutParams(0, dp(52), 1f))
        tabs.addView(search, LinearLayout.LayoutParams(0, dp(52), 1f))
        topBar.addView(title, FrameLayout.LayoutParams(-1, dp(68)))
        topBar.addView(settings, FrameLayout.LayoutParams(dp(64), dp(68), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(topBar, LinearLayout.LayoutParams(-1, dp(68)))
        root.addView(tabs)
        setContentView(root)
    }

    private fun clearContent() {
        while (root.childCount > 2) root.removeViewAt(2)
        resultPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(14), dp(18), dp(8))
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
            }, FrameLayout.LayoutParams(dp(205), dp(287), Gravity.CENTER))
        }
        status = TextView(this).apply {
            text = "Center one card in the frame. Hold steady while its name is read."
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(cameraFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(330)))
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
                if (lookupInFlight || now - lastLookupAt < scanPauseMillis()) { proxy.close(); return@setAnalyzer }
                val mediaImage = proxy.image
                if (mediaImage == null) { proxy.close(); return@setAnalyzer }
                val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        val lines = text.textBlocks.flatMap { it.lines }.map { it.text }
                        val candidate = bestCardName(lines)
                        if (candidate != null) {
                            val hints = printingHints(lines)
                            runOnUiThread {
                                pendingCardArt = captureCardArt()
                                lookupCard(candidate, hints.first, hints.second, fromCamera = true)
                            }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureCardArt(): Bitmap? {
        val frame = previewView.bitmap ?: return null
        // Crop the illustration inside the centered card guide. This is much larger and
        // more camera-friendly than the small expansion symbol.
        val cardWidth = frame.width * 0.52f
        val cardHeight = frame.height * 0.87f
        val cardLeft = (frame.width - cardWidth) / 2f
        val cardTop = (frame.height - cardHeight) / 2f
        val left = (cardLeft + cardWidth * 0.09f).toInt().coerceIn(0, frame.width - 2)
        val top = (cardTop + cardHeight * 0.12f).toInt().coerceIn(0, frame.height - 2)
        val width = (cardWidth * 0.82f).toInt().coerceAtMost(frame.width - left).coerceAtLeast(1)
        val height = (cardHeight * 0.42f).toInt().coerceAtMost(frame.height - top).coerceAtLeast(1)
        return runCatching { Bitmap.createBitmap(frame, left, top, width, height) }.getOrNull()
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
        lookupFromCamera = fromCamera
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
            .header("User-Agent", "BearJ3rksNerdScanner/0.9 (Android)")
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
                        .onSuccess { card -> runOnUiThread {
                            val scanned = lookupFromCamera
                            lookupFromCamera = false
                            val replaceHistoryId = historyReplacementId
                            historyReplacementId = null
                            finishLookup()
                            showCard(card)
                            if (scanned) recordScannedCard(card)
                            else if (replaceHistoryId != null) replaceLatestHistoryPrinting(replaceHistoryId, card)
                            lastLookupAt = System.currentTimeMillis()
                        } }
                        .onFailure { finishLookupError("Could not read Scryfall's response") }
                }
            }
        })
    }

    private fun showCard(card: JSONObject) {
        resultPanel.removeAllViews()
        val imageUrls = cardImageUrls(card)
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Matched Magic card image"
            setBackgroundColor(Color.rgb(231, 225, 214))
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        if (imageUrls.isNotEmpty()) loadCardImage(image, imageUrls)
        else image.setImageResource(android.R.drawable.ic_dialog_alert)
        val name = card.optString("name", "Unknown card")
        val setName = card.optString("set_name")
        val number = card.optString("collector_number")
        val prices = card.optJSONObject("prices")
        fun price(key: String, currency: String) = prices?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }?.let { "$currency$it" } ?: "—"
        val info = TextView(this).apply {
            text = "$name\n$setName · #$number\n\nUSD ${price("usd", "$")}   Foil ${price("usd_foil", "$")}"
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
        val changeSet = Button(this).apply {
            text = "CHANGE SET"
            isEnabled = card.optString("prints_search_uri").isNotBlank()
            setOnClickListener { loadPrintings(card) }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(open, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(4) })
            addView(changeSet, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(4) })
        }
        resultPanel.addView(TextView(this).apply {
            text = "MATCHED CARD"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(83, 69, 49))
            setPadding(0, 0, 0, dp(4))
        })
        resultPanel.addView(image, LinearLayout.LayoutParams(-1, dp(200)))
        resultPanel.addView(info)
        resultPanel.addView(Button(this).apply {
            text = "ADD TO MY LIST"
            setOnClickListener {
                addCardToList(card)
                text = "ADD ANOTHER COPY"
            }
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(4) })
        resultPanel.addView(actions, LinearLayout.LayoutParams(-1, dp(56)).apply {
            topMargin = dp(8)
            bottomMargin = 0
        })
        getSharedPreferences("recent", MODE_PRIVATE).edit()
            .putString("last_card", name).putString("last_uri", uri).apply()
        status.text = "Match found. Verify the set and collector number before using the price."
        pendingCardArt?.let { photographedArt ->
            pendingCardArt = null
            matchCardArtwork(card, photographedArt)
        }
    }

    private fun cardImageUrls(card: JSONObject): List<String> {
        fun fromUris(uris: JSONObject?): List<String> {
            if (uris == null) return emptyList()
            return listOf("normal", "large", "small", "png", "art_crop")
                .map { uris.optString(it) }.filter { it.startsWith("https://") }
        }
        val urls = fromUris(card.optJSONObject("image_uris")).toMutableList()
        val faces = card.optJSONArray("card_faces") ?: return urls.distinct()
        for (index in 0 until faces.length()) {
            urls += fromUris(faces.optJSONObject(index)?.optJSONObject("image_uris"))
        }
        return urls.distinct()
    }

    private fun historySnapshot(card: JSONObject, scannedAt: Long = System.currentTimeMillis()) = JSONObject().apply {
        put("id", card.optString("id")); put("name", card.optString("name"))
        put("set_name", card.optString("set_name")); put("set", card.optString("set"))
        put("collector_number", card.optString("collector_number")); put("scryfall_uri", card.optString("scryfall_uri"))
        put("prices", card.optJSONObject("prices") ?: JSONObject()); put("scanned_at", scannedAt)
    }

    private fun recordScannedCard(card: JSONObject) {
        val preferences = getSharedPreferences("scan_history", MODE_PRIVATE)
        val existing = runCatching { JSONArray(preferences.getString("cards", "[]")) }.getOrElse { JSONArray() }
        val updated = JSONArray().put(historySnapshot(card))
        for (index in 0 until minOf(existing.length(), 49)) updated.put(existing.optJSONObject(index))
        preferences.edit().putString("cards", updated.toString()).apply()
    }

    private fun replaceLatestHistoryPrinting(oldId: String, card: JSONObject) {
        val preferences = getSharedPreferences("scan_history", MODE_PRIVATE)
        val history = runCatching { JSONArray(preferences.getString("cards", "[]")) }.getOrElse { JSONArray() }
        for (index in 0 until history.length()) {
            val item = history.optJSONObject(index) ?: continue
            if (item.optString("id") == oldId) {
                history.put(index, historySnapshot(card, item.optLong("scanned_at", System.currentTimeMillis())))
                preferences.edit().putString("cards", history.toString()).apply()
                return
            }
        }
    }

    private fun showHistory() {
        clearContent()
        val preferences = getSharedPreferences("scan_history", MODE_PRIVATE)
        val history = runCatching { JSONArray(preferences.getString("cards", "[]")) }.getOrElse { JSONArray() }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        container.addView(TextView(this).apply {
            text = "Scan History"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(23, 21, 29))
            setPadding(0, 0, 0, dp(10))
        })
        if (history.length() == 0) container.addView(TextView(this).apply {
            text = "Your last 50 successful camera scans will appear here."
            textSize = 17f; gravity = Gravity.CENTER; setPadding(dp(12), dp(40), dp(12), dp(40))
        })
        for (index in 0 until history.length()) {
            val card = history.optJSONObject(index) ?: continue
            val prices = card.optJSONObject("prices")
            val usd = prices?.optString("usd")?.takeUnless { it == "null" || it.isBlank() } ?: "—"
            val foil = prices?.optString("usd_foil")?.takeUnless { it == "null" || it.isBlank() } ?: "—"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(7), dp(2), dp(7))
            }
            row.addView(TextView(this).apply {
                val preferred = card.optString("preferred_finish").takeIf { it.isNotBlank() }?.replaceFirstChar(Char::uppercase)
                text = "${index + 1}. ${card.optString("name")}${preferred?.let { " ($it preferred)" } ?: ""}\n${card.optString("set_name")} · ${card.optString("set").uppercase()} #${card.optString("collector_number")}\nUSD \$$usd   Foil \$$foil"
                textSize = 15f; setTextColor(Color.rgb(23, 21, 29))
                setOnClickListener {
                    card.optString("scryfall_uri").takeIf { it.isNotBlank() }
                        ?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                }
                setOnLongClickListener { showHistoryCardEditor(index, card); true }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "ADD"
                contentDescription = "Add ${card.optString("name")} to the active list"
                setOnClickListener { addCardToList(card) }
            }, LinearLayout.LayoutParams(dp(76), dp(50)))
            container.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (history.length() > 0) container.addView(Button(this).apply {
            text = "CLEAR HISTORY"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Clear scan history?")
                    .setMessage("This removes the saved scan history but does not change your card lists.")
                    .setPositiveButton("CLEAR") { _, _ -> preferences.edit().remove("cards").apply(); showHistory() }
                    .setNegativeButton("CANCEL", null).show()
            }
        })
        root.addView(ScrollView(this).apply { addView(container) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun loadCardImage(view: ImageView, urls: List<String>, index: Int = 0, lastError: String = "") {
        if (index >= urls.size) {
            runOnUiThread {
                view.setImageResource(android.R.drawable.ic_dialog_alert)
                view.contentDescription = "Card artwork failed to load${if (lastError.isBlank()) "" else ": $lastError"}"
                Toast.makeText(this, "Card details loaded, but the artwork download failed${if (lastError.isBlank()) "." else ": $lastError"}", Toast.LENGTH_LONG).show()
            }
            return
        }
        http.newCall(imageRequest(urls[index])).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = loadCardImage(view, urls, index + 1, e.localizedMessage ?: "network error")
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bytes = if (it.isSuccessful) it.body?.bytes() else null
                    val bitmap = bytes?.let { data -> BitmapFactory.decodeByteArray(data, 0, data.size) }
                    if (bitmap == null) loadCardImage(view, urls, index + 1, "HTTP ${it.code}")
                    else runOnUiThread { if (view.isAttachedToWindow) view.setImageBitmap(bitmap) }
                }
            }
        })
    }

    private fun addCardToList(card: JSONObject) {
        val prices = card.optJSONObject("prices")
        val choices = mutableListOf<Pair<String, Double>>()
        prices?.optString("usd")?.takeUnless { it.isBlank() || it == "null" }?.toDoubleOrNull()?.let { choices += "Non-foil" to it }
        prices?.optString("usd_foil")?.takeUnless { it.isBlank() || it == "null" }?.toDoubleOrNull()?.let { choices += "Foil" to it }
        if (choices.isEmpty()) choices += "Non-foil (price unavailable)" to 0.0
        val preferred = card.optString("preferred_finish")
        choices.firstOrNull { it.first.lowercase() == preferred }?.let {
            addCardWithFinish(card, it.first.lowercase(), it.second)
            return
        }
        if (choices.size == 1) addCardWithFinish(card, choices.first().first.substringBefore(" ").lowercase(), choices.first().second)
        else AlertDialog.Builder(this)
            .setTitle("Choose card finish")
            .setItems(choices.map { "${it.first} — \$${"%.2f".format(it.second)}" }.toTypedArray()) { _, which ->
                addCardWithFinish(card, choices[which].first.lowercase(), choices[which].second)
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun loadLists(): JSONObject {
        val preferences = getSharedPreferences("card_list", MODE_PRIVATE)
        val saved = preferences.getString("lists", null)
        if (saved != null) return runCatching { JSONObject(saved) }.getOrElse { JSONObject().put("My List", JSONArray()) }
        val oldCards = runCatching { JSONArray(preferences.getString("cards", "[]")) }.getOrElse { JSONArray() }
        for (index in 0 until oldCards.length()) oldCards.optJSONObject(index)?.put("finish", "non-foil")
        return JSONObject().put("My List", oldCards).also {
            preferences.edit().putString("lists", it.toString()).putString("active_list", "My List").remove("cards").apply()
        }
    }

    private fun saveLists(lists: JSONObject) = getSharedPreferences("card_list", MODE_PRIVATE)
        .edit().putString("lists", lists.toString()).apply()

    private fun activeListName(lists: JSONObject): String {
        val preferences = getSharedPreferences("card_list", MODE_PRIVATE)
        val requested = preferences.getString("active_list", "My List") ?: "My List"
        if (lists.has(requested)) return requested
        return lists.keys().asSequence().firstOrNull() ?: "My List"
    }

    private fun addCardWithFinish(card: JSONObject, finish: String, unitPrice: Double) {
        val lists = loadLists()
        val listName = activeListName(lists)
        val cards = lists.optJSONArray(listName) ?: JSONArray().also { lists.put(listName, it) }
        val id = card.optString("id")
        var existing: JSONObject? = null
        for (index in 0 until cards.length()) {
            cards.optJSONObject(index)?.takeIf { it.optString("id") == id && it.optString("finish", "non-foil") == finish }?.let { existing = it }
        }
        if (existing != null) existing!!.put("quantity", existing!!.optInt("quantity", 1) + 1)
        else cards.put(JSONObject().apply {
            put("id", id); put("name", card.optString("name")); put("set_name", card.optString("set_name"))
            put("set", card.optString("set").uppercase()); put("collector_number", card.optString("collector_number"))
            put("finish", finish); put("unit_price", unitPrice); put("quantity", 1)
            put("scryfall_uri", card.optString("scryfall_uri"))
        })
        saveLists(lists)
        Toast.makeText(this, "Added ${finish.replaceFirstChar(Char::uppercase)} to $listName", Toast.LENGTH_SHORT).show()
    }

    private fun showCardList() {
        clearContent()
        val preferences = getSharedPreferences("card_list", MODE_PRIVATE)
        val lists = loadLists()
        val listName = activeListName(lists)
        val cards = lists.optJSONArray(listName) ?: JSONArray()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val names = lists.keys().asSequence().toList()
        val selector = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(names.indexOf(listName).coerceAtLeast(0))
        }
        var initialSelection = true
        selector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initialSelection) { initialSelection = false; return }
                preferences.edit().putString("active_list", names[position]).apply(); showCardList()
            }
        }
        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(selector, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(Button(this@MainActivity).apply { text = "NEW LIST"; setOnClickListener { createNewList() } }, LinearLayout.LayoutParams(dp(120), dp(56)))
        }
        container.addView(TextView(this).apply { text = "My Lists"; textSize = 23f; gravity = Gravity.CENTER })
        container.addView(listHeader)
        if (cards.length() == 0) {
            container.addView(TextView(this).apply {
                text = "Your list is empty. Scan or search for a card, then tap Add to My List."
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(40), dp(12), dp(40))
            })
        }
        for (index in 0 until cards.length()) {
            val item = cards.optJSONObject(index) ?: continue
            val quantity = item.optInt("quantity", 1)
            val unit = item.optDouble("unit_price", 0.0)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(4), dp(8))
            }
            row.addView(TextView(this).apply {
                text = "${item.optString("name")} (${item.optString("finish", "non-foil").replaceFirstChar(Char::uppercase)})\n${item.optString("set_name")} · ${item.optString("set")} #${item.optString("collector_number")}\n$quantity × \$${"%.2f".format(unit)}  =  \$${"%.2f".format(unit * quantity)}"
                textSize = 16f
                setTextColor(Color.rgb(23, 21, 29))
                setOnClickListener {
                    val uri = item.optString("scryfall_uri")
                    if (uri.isNotBlank()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                }
                setOnLongClickListener { showListCardEditor(item); true }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "−1"
                contentDescription = "Remove one ${item.optString("name")}"
                setOnClickListener { adjustListQuantity(item.optString("id"), item.optString("finish", "non-foil"), -1) }
            }, LinearLayout.LayoutParams(dp(60), dp(50)))
            row.addView(Button(this).apply {
                text = "+1"
                contentDescription = "Add one ${item.optString("name")}"
                setOnClickListener { adjustListQuantity(item.optString("id"), item.optString("finish", "non-foil"), 1) }
            }, LinearLayout.LayoutParams(dp(60), dp(50)))
            container.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        var total = 0.0
        for (index in 0 until cards.length()) cards.optJSONObject(index)?.let { total += it.optDouble("unit_price", 0.0) * it.optInt("quantity", 1) }
        container.addView(TextView(this).apply {
            text = "Estimated total: \$${"%.2f".format(total)}"
            textSize = 23f; gravity = Gravity.CENTER; setTextColor(Color.rgb(23, 21, 29)); setPadding(0, dp(20), 0, dp(12))
        })
        if (cards.length() > 0) container.addView(Button(this).apply {
            text = "CLEAR LIST"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear My List?")
                    .setMessage("This removes every saved card from the list.")
                    .setPositiveButton("CLEAR") { _, _ ->
                        lists.put(listName, JSONArray()); saveLists(lists)
                        showCardList()
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
        })
        root.addView(ScrollView(this).apply { addView(container) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun adjustListQuantity(id: String, finish: String, delta: Int) {
        val lists = loadLists(); val listName = activeListName(lists)
        val cards = lists.optJSONArray(listName) ?: JSONArray(); val updated = JSONArray()
        for (index in 0 until cards.length()) {
            val item = cards.optJSONObject(index) ?: continue
            if (item.optString("id") == id && item.optString("finish", "non-foil") == finish) {
                val quantity = item.optInt("quantity", 1) + delta
                if (quantity > 0) { item.put("quantity", quantity); updated.put(item) }
            } else updated.put(item)
        }
        lists.put(listName, updated); saveLists(lists); showCardList()
    }

    private fun createNewList() {
        val input = EditText(this).apply { hint = "List name"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Create a new list").setView(input)
            .setPositiveButton("CREATE") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                val lists = loadLists()
                if (!lists.has(name)) lists.put(name, JSONArray())
                saveLists(lists)
                getSharedPreferences("card_list", MODE_PRIVATE).edit().putString("active_list", name).apply()
                showCardList()
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun showListCardEditor(item: JSONObject) {
        AlertDialog.Builder(this).setTitle("Edit ${item.optString("name")}")
            .setItems(arrayOf("Change set / printing", "Change foil / non-foil")) { _, which ->
                if (which == 0) choosePrintingForEdit(item.optString("id")) { replacement ->
                    replaceListCard(item, replacement, item.optString("finish", "non-foil"))
                } else fetchCardJson(item.optString("id")) { card -> showListFinishPicker(item, card) }
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun showListFinishPicker(item: JSONObject, card: JSONObject) {
        val prices = card.optJSONObject("prices")
        val choices = mutableListOf<Pair<String, Double>>()
        prices?.optString("usd")?.toDoubleOrNull()?.let { choices += "Non-foil — \$${"%.2f".format(it)}" to it }
        prices?.optString("usd_foil")?.toDoubleOrNull()?.let { choices += "Foil — \$${"%.2f".format(it)}" to it }
        if (choices.isEmpty()) { Toast.makeText(this, "No USD finish prices are available for this printing.", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("Choose finish")
            .setItems(choices.map { it.first }.toTypedArray()) { _, index ->
                val finish = if (choices[index].first.startsWith("Foil")) "foil" else "non-foil"
                replaceListCard(item, card, finish, choices[index].second)
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun replaceListCard(oldItem: JSONObject, card: JSONObject, finish: String, explicitPrice: Double? = null) {
        val lists = loadLists(); val listName = activeListName(lists)
        val cards = lists.optJSONArray(listName) ?: JSONArray(); val updated = JSONArray()
        val oldId = oldItem.optString("id"); val oldFinish = oldItem.optString("finish", "non-foil")
        val newId = card.optString("id"); val quantity = oldItem.optInt("quantity", 1)
        val priceKey = if (finish == "foil") "usd_foil" else "usd"
        val unitPrice = explicitPrice ?: card.optJSONObject("prices")?.optString(priceKey)?.toDoubleOrNull() ?: 0.0
        var replacement: JSONObject? = null
        for (index in 0 until cards.length()) {
            val existing = cards.optJSONObject(index) ?: continue
            if (existing.optString("id") == oldId && existing.optString("finish", "non-foil") == oldFinish) continue
            if (existing.optString("id") == newId && existing.optString("finish", "non-foil") == finish) replacement = existing
            else updated.put(existing)
        }
        if (replacement != null) {
            replacement!!.put("quantity", replacement!!.optInt("quantity", 1) + quantity)
            updated.put(replacement)
        } else updated.put(JSONObject().apply {
            put("id", newId); put("name", card.optString("name")); put("set_name", card.optString("set_name"))
            put("set", card.optString("set").uppercase()); put("collector_number", card.optString("collector_number"))
            put("finish", finish); put("unit_price", unitPrice); put("quantity", quantity)
            put("scryfall_uri", card.optString("scryfall_uri"))
        })
        lists.put(listName, updated); saveLists(lists); showCardList()
    }

    private fun showHistoryCardEditor(index: Int, card: JSONObject) {
        AlertDialog.Builder(this).setTitle("Edit ${card.optString("name")}")
            .setItems(arrayOf("Change set / printing", "Prefer non-foil", "Prefer foil", "Ask finish when adding")) { _, which ->
                when (which) {
                    0 -> choosePrintingForEdit(card.optString("id")) { replacement -> updateHistoryCard(index, replacement, card.optString("preferred_finish")) }
                    1 -> setHistoryPreferredFinish(index, card, "non-foil")
                    2 -> setHistoryPreferredFinish(index, card, "foil")
                    else -> setHistoryPreferredFinish(index, card, "")
                }
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun setHistoryPreferredFinish(index: Int, card: JSONObject, finish: String) {
        if (finish.isNotBlank()) {
            val key = if (finish == "foil") "usd_foil" else "usd"
            if (card.optJSONObject("prices")?.optString(key)?.toDoubleOrNull() == null) {
                Toast.makeText(this, "That finish has no USD price for this printing.", Toast.LENGTH_LONG).show(); return
            }
        }
        updateHistoryCard(index, card, finish)
    }

    private fun updateHistoryCard(index: Int, card: JSONObject, preferredFinish: String) {
        val preferences = getSharedPreferences("scan_history", MODE_PRIVATE)
        val history = runCatching { JSONArray(preferences.getString("cards", "[]")) }.getOrElse { JSONArray() }
        val previous = history.optJSONObject(index) ?: return
        val replacement = historySnapshot(card, previous.optLong("scanned_at", System.currentTimeMillis()))
        if (preferredFinish.isNotBlank()) replacement.put("preferred_finish", preferredFinish)
        history.put(index, replacement); preferences.edit().putString("cards", history.toString()).apply(); showHistory()
    }

    private fun choosePrintingForEdit(cardId: String, selected: (JSONObject) -> Unit) {
        fetchCardJson(cardId) { current ->
            val uri = current.optString("prints_search_uri")
            if (uri.isBlank()) { Toast.makeText(this, "No alternate printings were found.", Toast.LENGTH_SHORT).show(); return@fetchCardJson }
            Toast.makeText(this, "Loading available printings…", Toast.LENGTH_SHORT).show()
            fetchPrintingPage(uri, mutableListOf()) { printings ->
                ensureSetIcons {
                    runOnUiThread { showPrintingDialog(printings) { printing -> fetchCardJson(printing.id, selected) } }
                }
            }
        }
    }

    private fun fetchCardJson(id: String, success: (JSONObject) -> Unit) {
        http.newCall(apiRequest("https://api.scryfall.com/cards/${Uri.encode(id)}")).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                Toast.makeText(this@MainActivity, "Could not load that printing.", Toast.LENGTH_SHORT).show()
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val card = if (it.isSuccessful) runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull() else null
                    runOnUiThread {
                        if (card == null) Toast.makeText(this@MainActivity, "Could not load that printing.", Toast.LENGTH_SHORT).show()
                        else success(card)
                    }
                }
            }
        })
    }

    private fun loadPrintings(card: JSONObject) {
        val uri = card.optString("prints_search_uri")
        if (uri.isBlank() || lookupInFlight) return
        lookupInFlight = true
        progress.visibility = View.VISIBLE
        status.text = "Loading available sets…"
        fetchPrintingPage(uri, mutableListOf()) { printings ->
            if (printings.isEmpty()) {
                finishLookupError("No alternate printings found")
            } else ensureSetIcons {
                runOnUiThread {
                    finishLookup()
                    showPrintingDialog(printings) { lookupPrinting(it.id) }
                    status.text = "Choose the set and printing you want."
                }
            }
        }
    }

    private fun fetchPrintingPage(
        url: String,
        collected: MutableList<Printing>,
        done: (List<Printing>) -> Unit
    ) {
        val request = apiRequest(url)
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finishLookupError("Could not load printings")
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { finishLookupError("Could not load printings"); return }
                    val json = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                    if (json == null) { finishLookupError("Could not read printings"); return }
                    val data = json.optJSONArray("data")
                    if (data != null) for (index in 0 until data.length()) {
                        val item = data.optJSONObject(index) ?: continue
                        collected += Printing(
                            item.optString("id"), item.optString("set"),
                            item.optString("set_name"), item.optString("collector_number"),
                            artCropUrl(item).orEmpty()
                        )
                    }
                    val next = json.optString("next_page")
                    if (json.optBoolean("has_more") && next.isNotBlank()) fetchPrintingPage(next, collected, done)
                    else done(collected.distinctBy { printing -> printing.id })
                }
            }
        })
    }

    private fun artCropUrl(card: JSONObject): String? {
        card.optJSONObject("image_uris")?.optString("art_crop")
            ?.takeIf { it.startsWith("https://") }?.let { return it }
        val faces = card.optJSONArray("card_faces") ?: return null
        for (index in 0 until faces.length()) {
            faces.optJSONObject(index)?.optJSONObject("image_uris")?.optString("art_crop")
                ?.takeIf { it.startsWith("https://") }?.let { return it }
        }
        return null
    }

    private fun ensureSetIcons(done: () -> Unit) {
        if (setIcons.isNotEmpty()) { done(); return }
        http.newCall(apiRequest("https://api.scryfall.com/sets")).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done()
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val data = runCatching { JSONObject(it.body?.string().orEmpty()).optJSONArray("data") }.getOrNull()
                    if (data != null) for (index in 0 until data.length()) {
                        val set = data.optJSONObject(index) ?: continue
                        setIcons[set.optString("code")] = set.optString("icon_svg_uri")
                    }
                    done()
                }
            }
        })
    }

    private fun showPrintingDialog(printings: List<Printing>, onSelected: (Printing) -> Unit) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(list) }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle("Change set / printing")
            .setView(scroll)
            .setNegativeButton("CANCEL", null)
            .create()
        printings.forEach { printing ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = resources.getDrawable(android.R.drawable.list_selector_background, theme)
            }
            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setIcons[printing.setCode]?.takeIf(String::isNotBlank)?.let { url ->
                    load(url) { decoderFactory(SvgDecoder.Factory()) }
                }
            }
            val label = TextView(this).apply {
                text = "${printing.setName}\n${printing.setCode.uppercase()} · #${printing.collectorNumber}"
                textSize = 16f
                setTextColor(Color.rgb(23, 21, 29))
                setPadding(dp(12), dp(2), dp(4), dp(2))
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener {
                dialog.dismiss()
                onSelected(printing)
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(62)))
        }
        dialog.show()
    }

    private fun matchCardArtwork(card: JSONObject, photographedArt: Bitmap) {
        val uri = card.optString("prints_search_uri")
        if (uri.isBlank()) return
        lookupInFlight = true
        progress.visibility = View.VISIBLE
        status.text = "Card recognized. Comparing its artwork with known printings…"
        fetchPrintingPage(uri, mutableListOf()) { printings ->
            lifecycleScope.launch(Dispatchers.IO) {
                val photographedSignature = artworkSignature(photographedArt)
                val scored = printings.filter { it.artUrl.isNotBlank() }.distinctBy { it.artUrl }.mapNotNull { printing ->
                    val referenceSignature = cachedArtworkSignature(printing.id) ?: run {
                        val bitmap = downloadBitmap(printing.artUrl) ?: return@mapNotNull null
                        artworkSignature(bitmap).also { cacheArtworkSignature(printing.id, it) }
                    }
                    printing to artworkSimilarity(photographedSignature, referenceSignature)
                }.sortedByDescending { it.second }
                val best = scored.firstOrNull()
                runOnUiThread {
                    if (best == null) {
                        finishLookup()
                        status.text = "Card found, but its comparison artwork could not be loaded. Tap Change Set to choose manually."
                        return@runOnUiThread
                    }
                    val sameArtwork = printings.filter { it.artUrl == best.first.artUrl }
                    val selected = sameArtwork.firstOrNull { it.id == card.optString("id") } ?: sameArtwork.first()
                    val sharedNote = if (sameArtwork.size > 1) " This artwork appears in ${sameArtwork.size} printings." else ""
                    if (selected.id == card.optString("id")) {
                        finishLookup()
                        status.text = "Likely artwork match: ${selected.setName} #${selected.collectorNumber}.$sharedNote Verify with Change Set if needed."
                    } else {
                        status.text = "Likely artwork match: ${selected.setName} #${selected.collectorNumber}.$sharedNote Loading that printing…"
                        historyReplacementId = card.optString("id").takeIf { it.isNotBlank() }
                        lookupPrinting(selected.id)
                    }
                }
            }
        }
    }

    private fun artworkSignature(source: Bitmap): DoubleArray {
        val bitmap = Bitmap.createScaledBitmap(source, 12, 9, true)
        val values = DoubleArray(12 * 9 * 3)
        var position = 0
        for (y in 0 until 9) for (x in 0 until 12) {
            val pixel = bitmap.getPixel(x, y)
            values[position++] = Color.red(pixel) / 255.0
            values[position++] = Color.green(pixel) / 255.0
            values[position++] = Color.blue(pixel) / 255.0
        }
        // Standardize each color channel so exposure and camera white balance matter less.
        for (channel in 0..2) {
            val channelValues = (channel until values.size step 3).map { values[it] }
            val mean = channelValues.average()
            val deviation = sqrt(channelValues.sumOf { (it - mean) * (it - mean) } / channelValues.size).coerceAtLeast(0.05)
            for (index in channel until values.size step 3) values[index] = (values[index] - mean) / deviation
        }
        return values
    }

    private fun artworkSimilarity(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return -1.0
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (index in a.indices) { dot += a[index] * b[index]; aa += a[index] * a[index]; bb += b[index] * b[index] }
        return if (aa == 0.0 || bb == 0.0) 0.0 else dot / sqrt(aa * bb)
    }

    private fun cachedArtworkSignature(id: String): DoubleArray? {
        val encoded = getSharedPreferences("art_match_cache", MODE_PRIVATE).getString(id, null) ?: return null
        return runCatching { encoded.split(',').map(String::toDouble).toDoubleArray() }
            .getOrNull()?.takeIf { it.size == 12 * 9 * 3 }
    }

    private fun cacheArtworkSignature(id: String, signature: DoubleArray) {
        val cache = getSharedPreferences("art_match_cache", MODE_PRIVATE)
        val order = cache.getString("_order", "").orEmpty().split('|').filter { it.isNotBlank() }.toMutableList()
        order.remove(id); order += id
        val editor = cache.edit().putString(id, signature.joinToString(",") { "%.4f".format(java.util.Locale.US, it) })
        while (order.size > 300) editor.remove(order.removeAt(0))
        editor.putString("_order", order.joinToString("|")).apply()
    }

    private fun imageRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "BearJ3rksNerdScanner/0.9 (Android)")
        .header("Accept", "image/jpeg,image/png,image/*;q=0.8,*/*;q=0.5")
        .build()

    private fun downloadBitmap(url: String): Bitmap? = runCatching {
        http.newCall(imageRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    private fun lookupPrinting(id: String) {
        lookupInFlight = true
        progress.visibility = View.VISIBLE
        status.text = "Loading selected printing…"
        requestCard("https://api.scryfall.com/cards/${Uri.encode(id)}", null)
    }

    private fun apiRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "BearJ3rksNerdScanner/0.9 (Android)")
        .header("Accept", "application/json;q=0.9,*/*;q=0.8")
        .build()

    private fun showSettings() {
        val settings = getSharedPreferences("scanner_settings", MODE_PRIVATE)
        val pauseChoices = (1..5).map { "$it second${if (it == 1) "" else "s"}" }
        val pauseSelector = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, pauseChoices)
            setSelection((settings.getInt("scan_pause_seconds", 1) - 1).coerceIn(0, 4))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Pause after a successful scan"
                textSize = 17f
                setTextColor(Color.rgb(23, 21, 29))
            })
            addView(pauseSelector, LinearLayout.LayoutParams(-1, dp(56)))
            addView(Button(this@MainActivity).apply {
                text = "CLEAR ARTWORK CACHE"
                setOnClickListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        getSharedPreferences("art_match_cache", MODE_PRIVATE).edit().clear().apply()
                        runCatching { http.cache?.evictAll() }
                        runOnUiThread { Toast.makeText(this@MainActivity, "Artwork and image cache cleared", Toast.LENGTH_SHORT).show() }
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
            addView(TextView(this@MainActivity).apply {
                text = "Installed version: ${installedVersion()}\n\nUpdates are checked against the public GitHub releases for BearJ3rk's Nerd Scanner."
                setPadding(0, dp(12), 0, dp(4))
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Settings & Update")
            .setView(content)
            .setPositiveButton("SAVE") { _, _ ->
                settings.edit().putInt("scan_pause_seconds", pauseSelector.selectedItemPosition + 1).apply()
                Toast.makeText(this, "Scan pause saved", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("CHECK UPDATE") { _, _ -> checkForUpdate() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun scanPauseMillis(): Long = getSharedPreferences("scanner_settings", MODE_PRIVATE)
        .getInt("scan_pause_seconds", 1).coerceIn(1, 5) * 1000L

    private fun checkForUpdate() {
        Toast.makeText(this, "Checking GitHub for updates…", Toast.LENGTH_SHORT).show()
        http.newCall(apiRequest("https://api.github.com/repos/BearJ3rk/BearJ3rks-Nerd-Scanner/releases/latest"))
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                    Toast.makeText(this@MainActivity, "Update check failed. Try again later.", Toast.LENGTH_LONG).show()
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val release = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                        if (!it.isSuccessful || release == null) {
                            runOnUiThread { Toast.makeText(this@MainActivity, "Could not read the latest release.", Toast.LENGTH_LONG).show() }
                            return
                        }
                        val latest = release.optString("tag_name").removePrefix("v").removePrefix("V")
                        val releasePage = release.optString("html_url")
                        val assets = release.optJSONArray("assets")
                        var apkUrl = ""
                        if (assets != null) for (index in 0 until assets.length()) {
                            val asset = assets.optJSONObject(index) ?: continue
                            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                        val target = apkUrl.ifBlank { releasePage }
                        runOnUiThread { showUpdateResult(latest, target) }
                    }
                }
            })
    }

    private fun showUpdateResult(latest: String, downloadUrl: String) {
        if (latest.isBlank()) {
            Toast.makeText(this, "The latest version could not be identified.", Toast.LENGTH_LONG).show()
            return
        }
        if (compareVersions(latest, installedVersion()) <= 0) {
            AlertDialog.Builder(this)
                .setTitle("You're up to date")
                .setMessage("Version ${installedVersion()} is the newest available version.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Version $latest available")
            .setMessage("Download the new APK from the official GitHub release. Android will ask you to approve the installation.")
            .setPositiveButton("DOWNLOAD UPDATE") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            }
            .setNegativeButton("LATER", null)
            .show()
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    private fun installedVersion(): String = packageManager
        .getPackageInfo(packageName, 0).versionName ?: "0.0.0"

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
