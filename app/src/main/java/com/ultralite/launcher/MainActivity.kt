package com.ultralite.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Launcher mínimo: una sola Activity, UI creada por código (sin XML ni inflado),
 * cero dependencias, íconos cargados bajo demanda con caché acotada.
 */
class MainActivity : Activity() {

    private class App(val label: String, val lower: String, val component: ComponentName)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Caché de íconos limitada a ~6 MB (la clave se mide en KB).
    private val iconCache = object : LruCache<String, Bitmap>(6 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pending = HashSet<String>() // solo se toca desde el hilo principal

    private var allApps: List<App> = emptyList()
    private var shown: List<App> = emptyList()
    @Volatile private var loadToken = 0
    private var iconPx = 0

    private lateinit var search: EditText
    private lateinit var grid: GridView
    private val appAdapter = AppAdapter()

    private val reload = Runnable { loadApps() }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            main.removeCallbacks(reload)
            main.postDelayed(reload, 400)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false

        iconPx = dp(48)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(140, 0, 0, 0)) // velo para legibilidad sobre el fondo
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
        }

        search = EditText(this).apply {
            hint = "Buscar app"
            setHintTextColor(Color.argb(136, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            background = GradientDrawable().apply {
                setColor(Color.argb(51, 255, 255, 255))
                cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                    applyFilter()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, _, _ ->
                shown.firstOrNull()?.let { launch(it) }
                true
            }
        }
        root.addView(
            search,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) }
        )

        grid = GridView(this).apply {
            numColumns = 4
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            adapter = appAdapter
            setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ ->
                showMenu(shown[pos])
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideIme()
                }
                override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {}
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(pkgReceiver, filter, Context.RECEIVER_EXPORTED)

        loadApps()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Se pulsó Inicio estando ya en el launcher: volver al estado limpio.
        hideIme()
        search.setText("")
        search.clearFocus()
        grid.smoothScrollToPosition(0)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Un launcher no debe cerrarse con Atrás; solo limpia la búsqueda.
        if (search.text.isNotEmpty()) search.setText("")
        hideIme()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            iconCache.evictAll()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(pkgReceiver)
        io.shutdownNow()
        iconCache.evictAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- datos

    private fun loadApps() {
        val token = ++loadToken
        io.execute {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
            val apps = ArrayList<App>(list.size)
            for (ri in list) {
                val ai = ri.activityInfo
                val label = ri.loadLabel(pm).toString()
                apps.add(
                    App(label, label.lowercase(Locale.getDefault()), ComponentName(ai.packageName, ai.name))
                )
            }
            apps.sortBy { it.lower }
            main.post {
                if (token == loadToken) {
                    allApps = apps
                    applyFilter()
                }
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase(Locale.getDefault())
        shown = if (q.isEmpty()) {
            allApps
        } else {
            val starts = ArrayList<App>()
            val contains = ArrayList<App>()
            for (a in allApps) {
                if (a.lower.startsWith(q)) starts.add(a) else if (a.lower.contains(q)) contains.add(a)
            }
            starts + contains
        }
        appAdapter.notifyDataSetChanged()
    }

    private fun renderIcon(app: App): Bitmap? = try {
        val d = packageManager.getActivityIcon(app.component)
        val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, iconPx, iconPx)
        d.draw(Canvas(bmp))
        bmp
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------ acciones

    private fun launch(app: App) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i)
            search.setText("")
        } catch (e: Exception) {
            loadApps() // la app ya no existe o cambió
        }
    }

    private fun showMenu(app: App) {
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(arrayOf("Información de la app", "Desinstalar")) { _, which ->
                val uri = Uri.fromParts("package", app.component.packageName, null)
                val i = if (which == 0) {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
                } else {
                    Intent(Intent.ACTION_DELETE, uri)
                }
                try {
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    // sin acción posible
                }
            }
            .show()
    }

    private fun hideIme() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ------------------------------------------------------------- vistas

    private class Cell(ctx: Context, iconPx: Int) : LinearLayout(ctx) {
        val icon = ImageView(ctx)
        val label = TextView(ctx)
        var key: String? = null

        init {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val d = resources.displayMetrics.density
            val p = (8 * d).toInt()
            setPadding(p, p, p, p)
            addView(icon, LinearLayout.LayoutParams(iconPx, iconPx))
            label.apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }
            addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * d).toInt() }
            )
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? Cell) ?: Cell(this@MainActivity, iconPx)
            val app = shown[position]
            val key = app.component.flattenToShortString()
            cell.key = key
            cell.label.text = app.label

            val cached = iconCache.get(key)
            if (cached != null) {
                cell.icon.setImageBitmap(cached)
            } else {
                cell.icon.setImageDrawable(null)
                if (pending.add(key)) {
                    io.execute {
                        val bmp = renderIcon(app)
                        main.post {
                            pending.remove(key)
                            if (bmp != null) {
                                iconCache.put(key, bmp)
                                for (i in 0 until grid.childCount) {
                                    val c = grid.getChildAt(i) as? Cell ?: continue
                                    if (c.key == key) c.icon.setImageBitmap(bmp)
                                }
                            }
                        }
                    }
                }
            }
            return cell
        }
    }
}
