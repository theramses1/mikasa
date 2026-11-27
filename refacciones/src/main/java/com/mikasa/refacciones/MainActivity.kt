package com.mikasa.refacciones

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mikasa.refacciones.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var defaultUserAgent: String

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
        pendingGeoCallback = null
        pendingGeoOrigin = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.toolbar.inflateMenu(R.menu.main)
        binding.toolbar.setOnMenuItemClickListener { onMenuItem(it.itemId); true }
        binding.vehicleBar.setOnClickListener { showVehicles() }
        binding.retry.setOnClickListener { binding.webView.reload(); showOffline(false) }

        setupWebView()
        updateVehicleBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            val deepLink = intent?.data?.toString()
            val start = when {
                deepLink != null -> deepLink
                prefs.resumeLastPage && !prefs.lastUrl.isNullOrBlank() -> prefs.lastUrl!!
                else -> getString(R.string.home_url)
            }
            binding.webView.loadUrl(start)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { binding.webView.loadUrl(it.toString()) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // Guarda en disco las cookies (sesión, vehículo elegido en el sitio, carrito…)
        CookieManager.getInstance().flush()
        binding.webView.url?.let { if (it.startsWith("http")) prefs.lastUrl = it }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val web = binding.webView
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage: el sitio guarda aquí el vehículo
            @Suppress("DEPRECATION")
            databaseEnabled = true
            setGeolocationEnabled(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Algunos sitios bloquean el marcador "; wv" de WebView.
            defaultUserAgent = userAgentString.replace("; wv", "")
        }
        applyUserAgent()

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                showOffline(false)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.swipe.isRefreshing = false
                if (url != null && url.startsWith("http")) prefs.lastUrl = url
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    binding.swipe.isRefreshing = false
                    showOffline(true)
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.setProgressCompat(newProgress, true)
                binding.progress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    )
                }
            }
        }

        web.setDownloadListener { url, _, _, _, _ -> openExternal(Uri.parse(url)) }

        binding.swipe.setColorSchemeResources(R.color.brand)
        binding.swipe.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
        binding.swipe.setOnRefreshListener { web.reload() }
    }

    /** true = la app maneja el enlace fuera del WebView. */
    private fun handleUrl(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> false
            "intent" -> {
                try {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        intent.getStringExtra("browser_fallback_url")
                            ?.let { binding.webView.loadUrl(it) }
                    }
                } catch (_: Exception) {
                }
                true
            }
            else -> { // tel:, mailto:, whatsapp:, geo:, etc.
                openExternal(uri)
                true
            }
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast("No hay una app para abrir este enlace")
        }
    }

    private fun applyUserAgent() {
        binding.webView.settings.userAgentString = if (prefs.desktopMode) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        } else {
            defaultUserAgent
        }
    }

    private fun showOffline(show: Boolean) {
        binding.offline.visibility = if (show) View.VISIBLE else View.GONE
        binding.swipe.visibility = if (show) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ Menú

    private fun onMenuItem(id: Int) {
        when (id) {
            R.id.action_search -> showSearch()
            R.id.action_home -> binding.webView.loadUrl(getString(R.string.home_url))
            R.id.action_vehicles -> showVehicles()
            R.id.action_favorites -> showFavorites()
            R.id.action_add_favorite -> addFavorite()
            R.id.action_share -> sharePage()
            R.id.action_settings -> showSettings()
        }
    }

    private fun sharePage() {
        val url = binding.webView.url ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, binding.webView.title)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, "Compartir"))
    }

    // ------------------------------------------------------------------ Búsqueda

    private fun showSearch() {
        val vehicle = prefs.activeVehicle
        val layout = TextInputLayout(this).apply {
            hint = "¿Qué refacción buscas? (ej. balatas)"
        }
        val input = MaterialAutoCompleteTextView(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            threshold = 0
            setSimpleItems(prefs.recentSearches.toTypedArray())
            setOnFocusChangeListener { v, has -> if (has) (v as MaterialAutoCompleteTextView).showDropDown() }
        }
        layout.addView(input)
        val useVehicle = MaterialSwitch(this).apply {
            text = if (vehicle != null) "Para mi ${vehicle.description}" else "Sin vehículo activo"
            isChecked = vehicle != null && prefs.searchWithVehicle
            isEnabled = vehicle != null
        }
        val container = column(layout, useVehicle.padded())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Buscar refacción")
            .setView(container)
            .setPositiveButton("Buscar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        val doSearch = {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) {
                prefs.searchWithVehicle = useVehicle.isChecked
                prefs.recentSearches = listOf(q) + prefs.recentSearches.filter { it != q }
                val full = if (useVehicle.isChecked && vehicle != null) "$q ${vehicle.description}" else q
                binding.webView.loadUrl(prefs.searchTemplate.replace("{q}", Uri.encode(full)))
                dialog.dismiss()
            }
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { doSearch() }
            input.requestFocus()
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ Vehículos

    private fun updateVehicleBar() {
        val v = prefs.activeVehicle
        binding.vehicleBar.text = if (v != null) "🚗  Mi vehículo: ${v.label}  ▾" else getString(R.string.no_vehicle)
    }

    private fun showVehicles() {
        val vehicles = prefs.vehicles
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Mis vehículos")
            .setNeutralButton("Agregar") { _, _ -> editVehicle(null) }
            .setNegativeButton("Cerrar", null)

        if (vehicles.isEmpty()) {
            builder.setMessage(
                "Aún no tienes vehículos guardados.\n\nAgrega tus autos para buscar refacciones " +
                    "compatibles más rápido. Se quedan guardados en tu teléfono."
            )
        } else {
            val labels = vehicles.map { (if (it.id == prefs.activeVehicleId) "✓ " else "") + it.label }
            builder.setItems(labels.toTypedArray()) { _, which -> vehicleActions(vehicles[which]) }
        }
        builder.show()
    }

    private fun vehicleActions(v: Vehicle) {
        val isActive = v.id == prefs.activeVehicleId
        val options = arrayOf(
            if (isActive) "Dejar de usar como vehículo activo" else "Usar como vehículo activo",
            "Buscar refacciones para este vehículo",
            "Editar",
            "Eliminar"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(v.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        prefs.activeVehicleId = if (isActive) -1L else v.id
                        updateVehicleBar()
                        if (!isActive) toast("Vehículo activo: ${v.description}")
                    }
                    1 -> {
                        prefs.activeVehicleId = v.id
                        updateVehicleBar()
                        showSearch()
                    }
                    2 -> editVehicle(v)
                    3 -> MaterialAlertDialogBuilder(this)
                        .setMessage("¿Eliminar ${v.label}?")
                        .setPositiveButton("Eliminar") { _, _ ->
                            prefs.vehicles = prefs.vehicles.filter { it.id != v.id }
                            if (isActive) prefs.activeVehicleId = -1L
                            updateVehicleBar()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            .show()
    }

    private fun editVehicle(existing: Vehicle?) {
        val year = field("Año (ej. 2015)", existing?.year, InputType.TYPE_CLASS_NUMBER)
        val make = field("Marca (ej. Nissan)", existing?.make)
        val model = field("Modelo (ej. Versa)", existing?.model)
        val engine = field("Motor (opcional, ej. 1.6L 4 cil)", existing?.engine)
        val nick = field("Apodo (opcional, ej. El de mamá)", existing?.nickname)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Agregar vehículo" else "Editar vehículo")
            .setView(column(year.first, make.first, model.first, engine.first, nick.first))
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val y = year.second.text.toString().trim()
                val mk = make.second.text.toString().trim()
                val md = model.second.text.toString().trim()
                year.first.error = if (y.length != 4) "Año de 4 dígitos" else null
                make.first.error = if (mk.isEmpty()) "Requerido" else null
                model.first.error = if (md.isEmpty()) "Requerido" else null
                if (y.length != 4 || mk.isEmpty() || md.isEmpty()) return@setOnClickListener

                val v = Vehicle(
                    id = existing?.id ?: System.currentTimeMillis(),
                    year = y, make = mk, model = md,
                    engine = engine.second.text.toString().trim(),
                    nickname = nick.second.text.toString().trim(),
                )
                val list = prefs.vehicles.toMutableList()
                val idx = list.indexOfFirst { it.id == v.id }
                if (idx >= 0) list[idx] = v else list.add(v)
                prefs.vehicles = list
                // El primer vehículo agregado se vuelve el activo.
                if (prefs.activeVehicle == null) prefs.activeVehicleId = v.id
                updateVehicleBar()
                dialog.dismiss()
                toast("Vehículo guardado")
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ Favoritos

    private fun addFavorite() {
        val url = binding.webView.url ?: return
        val (layout, input) = field("Nombre", binding.webView.title ?: url)
        MaterialAlertDialogBuilder(this)
            .setTitle("Agregar a favoritos")
            .setView(column(layout))
            .setPositiveButton("Guardar") { _, _ ->
                val title = input.text.toString().ifBlank { url }
                prefs.favorites = prefs.favorites.filter { it.url != url } + Favorite(title, url)
                toast("Guardado en favoritos")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showFavorites() {
        val favs = prefs.favorites
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Favoritos")
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Agregar esta página") { _, _ -> addFavorite() }
        if (favs.isEmpty()) {
            builder.setMessage("Aún no tienes páginas favoritas.")
        } else {
            builder.setItems(favs.map { it.title }.toTypedArray()) { _, which ->
                val f = favs[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(f.title)
                    .setItems(arrayOf("Abrir", "Eliminar")) { _, action ->
                        if (action == 0) binding.webView.loadUrl(f.url)
                        else prefs.favorites = prefs.favorites.filter { it.url != f.url }
                    }
                    .show()
            }
        }
        builder.show()
    }

    // ------------------------------------------------------------------ Preferencias

    private fun showSettings() {
        val resume = MaterialSwitch(this).apply {
            text = "Abrir en la última página visitada"
            isChecked = prefs.resumeLastPage
        }
        val desktop = MaterialSwitch(this).apply {
            text = "Ver versión de escritorio"
            isChecked = prefs.desktopMode
        }
        val searchVehicle = MaterialSwitch(this).apply {
            text = "Incluir mi vehículo al buscar"
            isChecked = prefs.searchWithVehicle
        }
        val template = field("URL de búsqueda ({q} = texto)", prefs.searchTemplate, InputType.TYPE_TEXT_VARIATION_URI)

        MaterialAlertDialogBuilder(this)
            .setTitle("Preferencias")
            .setView(column(resume.padded(), desktop.padded(), searchVehicle.padded(), template.first))
            .setPositiveButton("Guardar") { _, _ ->
                prefs.resumeLastPage = resume.isChecked
                prefs.searchWithVehicle = searchVehicle.isChecked
                val t = template.second.text.toString().trim()
                prefs.searchTemplate = if (t.contains("{q}")) t else Prefs.DEFAULT_SEARCH_TEMPLATE
                if (desktop.isChecked != prefs.desktopMode) {
                    prefs.desktopMode = desktop.isChecked
                    applyUserAgent()
                    binding.webView.reload()
                }
            }
            .setNeutralButton("Borrar datos del sitio") { _, _ -> confirmClearSiteData() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmClearSiteData() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Borrar datos del sitio")
            .setMessage(
                "Se cerrará tu sesión en el sitio y se borrarán su carrito y caché. " +
                    "Tus vehículos y favoritos de la app NO se borran."
            )
            .setPositiveButton("Borrar") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
                binding.webView.clearCache(true)
                binding.webView.loadUrl(getString(R.string.home_url))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ Utilidades

    private fun field(
        hint: String, value: String?, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    ): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(this).apply { this.hint = hint }
        val edit = TextInputEditText(layout.context).apply {
            inputType = type
            setText(value ?: "")
            isSingleLine = true
        }
        layout.addView(edit)
        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        return layout to edit
    }

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        views.forEach { addView(it) }
    }

    private fun View.padded(): View = apply { setPadding(0, dp(8), 0, dp(8)) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
