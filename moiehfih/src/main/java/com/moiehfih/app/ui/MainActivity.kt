package com.moiehfih.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.moiehfih.app.R
import com.moiehfih.app.data.AppData
import com.moiehfih.app.data.Demo
import com.moiehfih.app.health.HealthSync
import java.time.LocalDate

/** Una pantalla de la app. Se reconstruye entera con [MainActivity.refresh] cuando cambian los datos. */
abstract class Screen(val act: MainActivity) {
    val data: AppData get() = act.data
    abstract val title: String

    /** Construye el contenido dentro de [root] (una columna con desplazamiento). */
    abstract fun build(root: LinearLayout)

    /** Mantener la pantalla encendida mientras esta pantalla está visible. */
    open val keepScreenOn = false
}

class MainActivity : Activity() {

    enum class Tab(val label: String, val icon: Int) {
        HOME("Inicio", R.drawable.ic_home),
        DIET("Dieta", R.drawable.ic_diet),
        TRAIN("Entreno", R.drawable.ic_train),
        WEIGHT("Peso", R.drawable.ic_weight),
        JOURNAL("Bitácora", R.drawable.ic_journal),
    }

    lateinit var data: AppData
        private set

    private var tab = Tab.HOME
    private val stack = ArrayList<Screen>()
    private val scrollPositions = HashMap<Screen, Int>()

    private lateinit var titleView: TextView
    private lateinit var backButton: ImageView
    private lateinit var settingsButton: ImageView
    private lateinit var container: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var restBar: LinearLayout
    private lateinit var restText: TextView
    private var currentScroll: ScrollView? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        data = AppData.get(this)
        applyDebugExtras(intent)
        savedInstanceState?.getString("tab")?.let { name -> Tab.values().firstOrNull { it.name == name }?.let { tab = it } }
        setContentView(buildShell())
        resetStack()
        render()
        if (data.profile != null) openDebugScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
    }

    override fun onResume() {
        super.onResume()
        // Al volver otro día, las pantallas de "hoy" deben mostrar la nueva fecha.
        refresh()
        tickRest()
        // Sincronización automática con Health Connect si pasó más de media hora.
        val last = data.healthLastSync ?: 0L
        if (data.healthConnected && System.currentTimeMillis() - last > 30 * 60_000L) syncHealth(quiet = true)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Solo en compilaciones de depuración: permite abrir la app con datos de demostración y en
     * una pestaña concreta (lo usan las pruebas en emulador para tomar capturas).
     */
    private fun applyDebugExtras(intent: Intent?) {
        if (intent == null || applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent.getBooleanExtra("demo", false)) Demo.load(data)
        intent.getStringExtra("tab")?.let { name -> Tab.values().firstOrNull { it.name.equals(name, true) }?.let { tab = it } }
        debugOpen = intent.getStringExtra("open")
        if (debugOpen == "workout" && data.activeWorkout() == null) {
            data.routines.firstOrNull()?.let { WorkoutActions.start(data, it) }
        }
    }

    private var debugOpen: String? = null

    private fun openDebugScreen() {
        when (debugOpen) {
            "workout" -> push(ActiveWorkoutScreen(this))
            "settings" -> push(SettingsScreen(this))
            "exercise" -> data.exercises.firstOrNull { it.name == "Press de banca" }?.let { push(ExerciseDetailScreen(this, it.id)) }
            "food" -> push(FoodSearchScreen(this, LocalDate.now(), com.moiehfih.app.data.Meal.COMIDA))
            "health" -> push(HealthScreen(this))
            "cardio" -> push(CardioScreen(this))
        }
        debugOpen = null
    }

    // ---------- Estructura ----------

    private fun buildShell(): View {
        val root = vertical { setBackgroundColor(Pal.bg) }
        window.statusBarColor = Pal.bg
        window.navigationBarColor = Pal.surface
        if (!Pal.dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val top = horizontal { setPadding(dp(8), dp(10), dp(8), dp(6)) }
        backButton = iconButton(R.drawable.ic_back, "Atrás") { onBackPressedCompat() }
        top.add(backButton, width = dp(44), height = dp(44))
        titleView = top.add(TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Pal.text)
            maxLines = 1
            setPadding(dp(8), 0, 0, 0)
        }, width = 0, weight = 1f)
        settingsButton = iconButton(R.drawable.ic_settings, "Ajustes") { push(SettingsScreen(this)) }
        top.add(settingsButton, width = dp(44), height = dp(44))
        root.add(top)

        container = root.add(FrameLayout(this), height = 0, weight = 1f)

        restBar = root.add(horizontal {
            setBackgroundColor(Pal.accent)
            setPadding(dp(16), dp(8), dp(8), dp(8))
            visibility = View.GONE
        })
        restText = restBar.add(TextView(this).apply {
            setTextColor(Pal.onAccent)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, width = 0, weight = 1f)
        for ((label, delta) in listOf("−15" to -15, "+15" to 15, "Saltar" to 0)) {
            restBar.add(TextView(this).apply {
                text = label
                setTextColor(Pal.onAccent)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { if (delta == 0) stopRest() else adjustRest(delta) }
            }, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        navBar = root.add(horizontal {
            setBackgroundColor(Pal.surface)
            setPadding(0, dp(4), 0, dp(4))
        })
        root.add(View(this).apply { setBackgroundColor(Pal.border) }, height = 1)
        return root
    }

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(Pal.text)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        background = clickable(rounded(0, dp(22).toFloat()), dp(22).toFloat())
        setOnClickListener { onClick() }
    }

    private fun buildNav() {
        navBar.removeAllViews()
        for (t in Tab.values()) {
            val on = t == tab
            val item = vertical {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
                background = clickable(rounded(0, dp(12).toFloat()), dp(12).toFloat())
                contentDescription = t.label
                setOnClickListener { selectTab(t) }
            }
            item.add(ImageView(this).apply {
                setImageResource(t.icon)
                setColorFilter(if (on) Pal.accent else Pal.muted)
            }, width = dp(24), height = dp(24))
            item.add(TextView(this).apply {
                text = t.label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (on) Pal.accent else Pal.muted)
            })
            navBar.add(item, width = 0, weight = 1f)
        }
    }

    private fun rootScreen(t: Tab): Screen = when (t) {
        Tab.HOME -> HomeScreen(this)
        Tab.DIET -> DietScreen(this)
        Tab.TRAIN -> TrainScreen(this)
        Tab.WEIGHT -> WeightScreen(this)
        Tab.JOURNAL -> JournalScreen(this)
    }

    private fun resetStack() {
        stack.clear()
        scrollPositions.clear()
        stack += if (data.profile == null) OnboardingScreen(this) else rootScreen(tab)
    }

    fun selectTab(t: Tab) {
        if (t == tab && stack.size == 1) return
        tab = t
        resetStack()
        render(scrollTop = true)
    }

    /** Vuelve a la estructura normal tras el onboarding, importar datos o borrar todo. */
    fun restart(t: Tab = Tab.HOME) {
        tab = t
        resetStack()
        render(scrollTop = true)
    }

    fun push(s: Screen) {
        currentScroll?.let { scrollPositions[stack.last()] = it.scrollY }
        stack += s
        render(scrollTop = true)
    }

    fun pop() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            render()
        }
    }

    /** Reemplaza la pantalla actual por otra (sin poder volver a la anterior). */
    fun replace(s: Screen) {
        stack[stack.size - 1] = s
        render(scrollTop = true)
    }

    /** Reconstruye la pantalla actual conservando el desplazamiento (o volviendo arriba). */
    fun refresh(scrollTop: Boolean = false) {
        if (stack.isEmpty()) return
        currentScroll?.let { scrollPositions[stack.last()] = it.scrollY }
        render(scrollTop)
    }

    private fun render(scrollTop: Boolean = false) {
        val screen = stack.last()
        val onboarding = screen is OnboardingScreen
        titleView.text = screen.title
        backButton.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        settingsButton.visibility = if (stack.size == 1 && !onboarding) View.VISIBLE else View.GONE
        navBar.visibility = if (onboarding) View.GONE else View.VISIBLE
        if (!onboarding) buildNav()
        if (screen.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val column = vertical { setPadding(dp(16), dp(4), dp(16), dp(24)) }
        screen.build(column)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        container.removeAllViews()
        container.addView(scroll)
        currentScroll = scroll
        val y = if (scrollTop) 0 else scrollPositions[screen] ?: 0
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = onBackPressedCompat()

    private fun onBackPressedCompat() {
        when {
            stack.size > 1 -> pop()
            tab != Tab.HOME && data.profile != null -> selectTab(Tab.HOME)
            else -> finish()
        }
    }

    // ---------- Utilidades para las pantallas ----------

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun confirm(message: String, yes: String = "Sí", onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Diálogo con un formulario. [onSave] devuelve un mensaje de error para mantener el diálogo
     * abierto, o null si todo fue bien.
     */
    fun formDialog(title: String, positive: String = "Guardar", build: LinearLayout.() -> Unit, onSave: () -> String?) {
        val body = vertical {
            setPadding(dp(20), dp(8), dp(20), 0)
            build()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(positive, null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val error = onSave()
                if (error == null) dialog.dismiss() else toast(error)
            }
        }
        dialog.show()
    }

    fun pickDate(initial: LocalDate, max: LocalDate? = LocalDate.now(), onPick: (LocalDate) -> Unit) {
        val dlg = DatePickerDialog(this, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        if (max != null) dlg.datePicker.maxDate = max.atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        dlg.show()
    }

    // ---------- Temporizador de descanso ----------

    private var restEndsAt = 0L

    fun startRest(seconds: Int = data.restSeconds) {
        if (seconds <= 0) return
        restEndsAt = System.currentTimeMillis() + seconds * 1000L
        tickRest()
    }

    private fun adjustRest(delta: Int) {
        restEndsAt += delta * 1000L
        tickRest()
    }

    private fun stopRest() {
        restEndsAt = 0
        tickRest()
    }

    private fun tickRest() {
        handler.removeCallbacksAndMessages(null)
        val left = ((restEndsAt - System.currentTimeMillis()) / 1000).toInt()
        if (restEndsAt == 0L || left < 0) {
            if (restEndsAt != 0L) {
                restEndsAt = 0
                vibrate()
                toast("¡Descanso terminado!")
            }
            restBar.visibility = View.GONE
            return
        }
        restBar.visibility = View.VISIBLE
        restText.text = String.format(Fmt.locale, "Descanso  %d:%02d", left / 60, left % 60)
        handler.postDelayed({ tickRest() }, 250)
    }

    private fun vibrate() {
        @Suppress("DEPRECATION")
        val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }

    // ---------- Exportar / importar ----------

    private var pendingExport: String? = null

    fun exportBackup() {
        pendingExport = data.exportJson()
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "moiehfih-${LocalDate.now()}.json"),
            REQUEST_EXPORT,
        )
    }

    fun importBackup() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            REQUEST_IMPORT,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)
        val uri = result?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQUEST_EXPORT -> {
                    val content = pendingExport ?: return
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    pendingExport = null
                    toast("Copia de seguridad guardada")
                }
                REQUEST_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
                    confirm("Esto reemplazará todos tus datos actuales por los de la copia. ¿Continuar?", "Importar") {
                        try {
                            data.importJson(text)
                            restart()
                            toast("Datos importados")
                        } catch (e: Exception) {
                            toast("No se pudo importar: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    // ---------- Health Connect ----------

    var healthSyncing = false
        private set

    /** Pide los permisos de Health Connect (Android 14+) y sincroniza. */
    fun connectHealth() {
        if (!HealthSync.isSupported) {
            toast("Health Connect integrado requiere Android 14 o superior.")
            return
        }
        requestPermissions(HealthSync.PERMISSIONS, REQUEST_HEALTH)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_HEALTH) return
        if (HealthSync.grantedPermissions(this).isEmpty()) {
            toast("Sin permisos de Health Connect no se pueden importar datos.")
            refresh()
            return
        }
        data.setHealthState(true, data.healthLastSync)
        syncHealth(quiet = false)
    }

    fun disconnectHealth() {
        data.setHealthState(false, data.healthLastSync)
        refresh()
        toast("Desconectado. Para quitar los permisos, ábrelos en Health Connect.")
    }

    fun openHealthConnectSettings() {
        try {
            startActivity(
                Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, packageName),
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))
            } catch (e2: Exception) {
                toast("No se encontró Health Connect en este teléfono.")
            }
        }
    }

    /** Importa en segundo plano los últimos días (30 la primera vez) y guarda en el hilo principal. */
    fun syncHealth(quiet: Boolean) {
        if (!HealthSync.isSupported || healthSyncing) return
        if (HealthSync.grantedPermissions(this).isEmpty()) {
            if (!quiet) connectHealth()
            return
        }
        healthSyncing = true
        if (!quiet) {
            toast("Sincronizando con Health Connect…")
            refresh()
        }
        val today = LocalDate.now()
        val last = data.healthLastSync?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }
        val from = if (last == null) today.minusDays(29) else minOf(last.minusDays(2), today)
        val weight = data.derived().trendNow ?: 75.0
        Thread {
            val fetched = try {
                HealthSync.fetch(this, from, today, weight)
            } catch (e: Exception) {
                HealthSync.Fetched(emptyList(), emptyList(), listOf(e.message ?: e.javaClass.simpleName))
            }
            runOnUiThread {
                healthSyncing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val summary = HealthSync.apply(data, fetched)
                if (!quiet) {
                    val msg = buildString {
                        append("Importados ${summary.days} días")
                        if (summary.sessions > 0) append(", ${summary.sessions} sesiones de cardio")
                        if (summary.weights > 0) append(", ${summary.weights} pesos")
                        if (summary.errors.isNotEmpty()) append(". Algunos datos fallaron: ${summary.errors.first()}")
                    }
                    toast(msg)
                }
                refresh()
            }
        }.start()
    }

    companion object {
        private const val REQUEST_EXPORT = 1
        private const val REQUEST_IMPORT = 2
        private const val REQUEST_HEALTH = 3
    }
}
