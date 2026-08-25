package com.bearj3rk.nerdscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.appcompat.app.AlertDialog
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
    private val setIcons = mutableMapOf<String, String>()

    private data class Printing(
        val id: String,
        val setCode: String,
        val setName: String,
        val collectorNumber: String
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
        val search = Button(this).apply { text = "SEARCH NAME"; setOnClickListener { showManualSearch() } }
        tabs.addView(scan, LinearLayout.LayoutParams(0, dp(52), 1f))
        tabs.addView(cardList, LinearLayout.LayoutParams(0, dp(52), 1f))
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
            .header("User-Agent", "BearJ3rksNerdScanner/0.4 (Android)")
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
        resultPanel.addView(image, LinearLayout.LayoutParams(-1, dp(280)))
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
            bottomMargin = dp(24)
        })
        getSharedPreferences("recent", MODE_PRIVATE).edit()
            .putString("last_card", name).putString("last_uri", uri).apply()
        status.text = "Match found. Verify the set and collector number before using the price."
    }

    private fun addCardToList(card: JSONObject) {
        val preferences = getSharedPreferences("card_list", MODE_PRIVATE)
        val cards = runCatching { org.json.JSONArray(preferences.getString("cards", "[]")) }
            .getOrElse { org.json.JSONArray() }
        val id = card.optString("id")
        var existing: JSONObject? = null
        for (index in 0 until cards.length()) {
            val item = cards.optJSONObject(index)
            if (item?.optString("id") == id) { existing = item; break }
        }
        if (existing != null) {
            existing.put("quantity", existing.optInt("quantity", 1) + 1)
        } else {
            val prices = card.optJSONObject("prices")
            val usd = prices?.optString("usd")?.toDoubleOrNull()
                ?: prices?.optString("usd_foil")?.toDoubleOrNull() ?: 0.0
            cards.put(JSONObject().apply {
                put("id", id)
                put("name", card.optString("name"))
                put("set_name", card.optString("set_name"))
                put("set", card.optString("set").uppercase())
                put("collector_number", card.optString("collector_number"))
                put("unit_price", usd)
                put("quantity", 1)
                put("scryfall_uri", card.optString("scryfall_uri"))
            })
        }
        preferences.edit().putString("cards", cards.toString()).apply()
        Toast.makeText(this, "Added to My List", Toast.LENGTH_SHORT).show()
    }

    private fun showCardList() {
        clearContent()
        val preferences = getSharedPreferences("card_list", MODE_PRIVATE)
        val cards = runCatching { org.json.JSONArray(preferences.getString("cards", "[]")) }
            .getOrElse { org.json.JSONArray() }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        var total = 0.0
        for (index in 0 until cards.length()) {
            val item = cards.optJSONObject(index) ?: continue
            total += item.optDouble("unit_price", 0.0) * item.optInt("quantity", 1)
        }
        container.addView(TextView(this).apply {
            text = "My Card List\nEstimated total: \$${"%.2f".format(total)}"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(23, 21, 29))
            setPadding(0, dp(4), 0, dp(16))
        })
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
                text = "${item.optString("name")}\n${item.optString("set_name")} · ${item.optString("set")} #${item.optString("collector_number")}\n$quantity × \$${"%.2f".format(unit)}  =  \$${"%.2f".format(unit * quantity)}"
                textSize = 16f
                setTextColor(Color.rgb(23, 21, 29))
                setOnClickListener {
                    val uri = item.optString("scryfall_uri")
                    if (uri.isNotBlank()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "−1"
                contentDescription = "Remove one ${item.optString("name")}"
                setOnClickListener { removeOneFromList(item.optString("id")) }
            }, LinearLayout.LayoutParams(dp(64), dp(50)))
            container.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (cards.length() > 0) container.addView(Button(this).apply {
            text = "CLEAR LIST"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear My List?")
                    .setMessage("This removes every saved card from the list.")
                    .setPositiveButton("CLEAR") { _, _ ->
                        preferences.edit().remove("cards").apply()
                        showCardList()
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
        })
        root.addView(ScrollView(this).apply { addView(container) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun removeOneFromList(id: String) {
        val preferences = getSharedPreferences("card_list", MODE_PRIVATE)
        val cards = runCatching { org.json.JSONArray(preferences.getString("cards", "[]")) }
            .getOrElse { org.json.JSONArray() }
        val updated = org.json.JSONArray()
        for (index in 0 until cards.length()) {
            val item = cards.optJSONObject(index) ?: continue
            if (item.optString("id") == id) {
                val quantity = item.optInt("quantity", 1) - 1
                if (quantity > 0) { item.put("quantity", quantity); updated.put(item) }
            } else updated.put(item)
        }
        preferences.edit().putString("cards", updated.toString()).apply()
        showCardList()
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
                    showPrintingDialog(printings)
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
                            item.optString("set_name"), item.optString("collector_number")
                        )
                    }
                    val next = json.optString("next_page")
                    if (json.optBoolean("has_more") && next.isNotBlank()) fetchPrintingPage(next, collected, done)
                    else done(collected.distinctBy { printing -> printing.id })
                }
            }
        })
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

    private fun showPrintingDialog(printings: List<Printing>) {
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
                setIcons[printing.setCode]?.takeIf(String::isNotBlank)?.let { load(it) }
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
                lookupPrinting(printing.id)
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(62)))
        }
        dialog.show()
    }

    private fun lookupPrinting(id: String) {
        lookupInFlight = true
        progress.visibility = View.VISIBLE
        status.text = "Loading selected printing…"
        requestCard("https://api.scryfall.com/cards/${Uri.encode(id)}", null)
    }

    private fun apiRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "BearJ3rksNerdScanner/0.4 (Android)")
        .header("Accept", "application/json;q=0.9,*/*;q=0.8")
        .build()

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("Update & About")
            .setMessage("Installed version: ${installedVersion()}\n\nUpdates are securely checked against the public GitHub releases for BearJ3rk's Nerd Scanner.")
            .setPositiveButton("CHECK FOR UPDATE") { _, _ -> checkForUpdate() }
            .setNegativeButton("CLOSE", null)
            .show()
    }

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
