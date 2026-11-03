package com.andrerinas.openheadunit.main

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.AppDrawerTabsPolicy.Tab
import com.andrerinas.openheadunit.main.settings.SettingsDrawables
import com.andrerinas.openheadunit.main.settings.SettingsPalette
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppDrawerItem(
    val label: String,
    val packageName: String,
    val activityName: String,
    /** Set when this item is one of the app's shortcuts instead of the app itself. */
    val shortcutId: String? = null,
    /** For a shortcut, the name of the app it belongs to. */
    val appLabel: String? = null
) {
    /** Key of this item's icon in [AppDrawerCache], unique per app and per shortcut. */
    val iconKey: String
        get() = if (shortcutId == null) appIconKey else "$packageName/$shortcutId"

    /** Key of the owning app's icon, which a shortcut wears as its badge. */
    val appIconKey: String
        get() = "$packageName/$activityName"

    /** What a favorite slot stores for this item. */
    fun favoriteTarget(): FavoriteTarget =
        if (shortcutId == null) FavoriteTarget.App(packageName)
        else FavoriteTarget.Shortcut(packageName, shortcutId)
}

object AppDrawerCache {
    @Volatile
    var cachedApps: List<AppDrawerItem>? = null
    val iconCache = LruCache<String, Drawable>(150)

    fun invalidate() {
        cachedApps = null
        iconCache.evictAll()
    }
}

class AppDrawerFragment : DialogFragment() {

    companion object {
        private const val TAG = "AppDrawerFragment"
        private const val ARG_FAVORITE_SLOT = "favorite_slot"
        private const val STATE_TAB = "drawer_tab"

        /** Opens the drawer to choose the app for one of the home screen's favorite slots. */
        fun pickFavoriteArgs(slot: Int) = Bundle().apply { putInt(ARG_FAVORITE_SLOT, slot) }

        /** @return false when the package has no launcher entry or refused to start. */
        fun launchPackage(context: Context, packageName: String): Boolean {
            return try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                AppLog.e(TAG, "Launch failed for $packageName", e)
                false
            }
        }

        fun preload(context: Context) {
            if (AppDrawerCache.cachedApps != null) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    queryInstalledApps(context.applicationContext)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Preload failed", e)
                }
            }
        }

        fun invalidateCache() {
            AppDrawerCache.invalidate()
        }

        internal fun queryInstalledApps(context: Context): List<AppDrawerItem> {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }

            val ownPackage = context.packageName
            val appsList = resolved.mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val pkg = activityInfo.packageName
                if (pkg == ownPackage) null
                else {
                    val label = resolveInfo.loadLabel(pm).toString().trim()
                    val displayLabel = if (label.isNullOrEmpty()) activityInfo.name else label
                    AppDrawerItem(
                        label = displayLabel,
                        packageName = pkg,
                        activityName = activityInfo.name
                    )
                }
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

            // A shortcut belongs to its app, so it is listed right under it. Only the first
            // launcher activity of a package carries them, so they never show twice. Empty
            // outside launcher mode, where the shortcuts cannot be read.
            val shortcuts = LauncherShortcuts.allByPackage(context)
            val withShortcuts = mutableListOf<AppDrawerItem>()
            val seen = mutableSetOf<String>()
            for (item in appsList) {
                withShortcuts.add(item)
                if (!seen.add(item.packageName)) continue
                for (shortcut in shortcuts[item.packageName].orEmpty()) {
                    withShortcuts.add(
                        AppDrawerItem(
                            label = shortcut.label,
                            packageName = item.packageName,
                            activityName = item.activityName,
                            shortcutId = shortcut.shortcutId,
                            appLabel = item.label
                        )
                    )
                }
            }

            AppDrawerCache.cachedApps = withShortcuts
            return withShortcuts
        }

        /** The icon of an app or one of its shortcuts, or null when the package is gone. */
        internal fun iconFor(context: Context, item: AppDrawerItem): Drawable? {
            if (item.shortcutId != null) {
                LauncherShortcuts.icon(context, item.packageName, item.shortcutId)?.let { return it }
            }
            val pm = context.packageManager
            return try {
                pm.getActivityIcon(ComponentName(item.packageName, item.activityName))
            } catch (_: Exception) {
                try {
                    pm.getApplicationIcon(item.packageName)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /** Everything the last query returned; the grid shows the part [chosenTab] asks for. */
    private var allItems: List<AppDrawerItem> = emptyList()
    private var chosenTab = Tab.APPS
    private var adapter: AppDrawerAdapter? = null
    private var tabGroup: MaterialButtonToggleGroup? = null
    private var emptyView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppTheme_Fullscreen)
        chosenTab = savedInstanceState?.getString(STATE_TAB)
            ?.let { name -> Tab.values().firstOrNull { it.name == name } } ?: Tab.APPS
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, chosenTab.name)
    }

    override fun onDestroyView() {
        adapter = null
        tabGroup = null
        emptyView = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        if (dialog != null) {
            view?.let { applyHudMirroring(it) }
        }
    }

    private fun applyHudMirroring(view: View) {
        val ctx = context ?: return
        val appSettings = App.provide(ctx).settings
        val mirror = if (appSettings.hudMirroring) -1.0f else 1.0f
        dialog?.window?.let { win ->
            val root = win.findViewById<View>(android.R.id.content) ?: win.decorView
            root.scaleX = mirror
        } ?: run {
            view.scaleX = mirror
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_app_drawer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val rvApps = view.findViewById<RecyclerView>(R.id.rv_apps)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)
        emptyView = tvEmpty
        setUpTabs(view)

        toolbar.setNavigationOnClickListener {
            closeDrawer()
        }

        val screenWidthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        rvApps.layoutManager = GridLayoutManager(requireContext(), (screenWidthDp / 120).toInt().coerceAtLeast(3))

        val favoriteSlot = arguments?.getInt(ARG_FAVORITE_SLOT, -1) ?: -1
        if (favoriteSlot >= 0) toolbar.setTitle(R.string.home_choose_favorite_app)
        val adapter = AppDrawerAdapter(
            emptyList(),
            viewLifecycleOwner.lifecycleScope,
            onItemClick = { app -> if (favoriteSlot >= 0) pickFavorite(favoriteSlot, app) else launchApp(app) },
            onItemLongClick = { anchor, app -> if (favoriteSlot < 0) showAddToFavorites(anchor, app) }
        )
        rvApps.adapter = adapter
        this.adapter = adapter

        // If list is cached, display immediately without waiting for query
        val cached = AppDrawerCache.cachedApps
        if (cached != null) {
            progressBar.visibility = View.GONE
            showItems(cached)
            warmupIcons(cached)
        } else {
            progressBar.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
        }

        // Query in background to populate (or update) app list
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val apps = try {
                queryInstalledApps(requireContext().applicationContext)
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to query launcher apps", e)
                emptyList()
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                progressBar.visibility = View.GONE
                showItems(apps)
                warmupIcons(apps)
            }
        }
    }

    private fun setUpTabs(view: View) {
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.drawer_tab_group) ?: return
        tabGroup = group
        if (!AppDrawerTabsPolicy.tabsInToolbar(resources.configuration.screenWidthDp)) {
            // Narrow screen: a row of their own under the toolbar, so the title is not pushed out.
            val column = view as? ViewGroup
            (group.parent as? ViewGroup)?.removeView(group)
            column?.addView(
                group,
                1,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
            )
        }
        val ctx = view.context
        val palette = SettingsPalette.from(ctx)
        val radius = resources.getDimensionPixelSize(R.dimen.settings_tab_height) / 2
        for (id in listOf(R.id.drawer_tab_apps, R.id.drawer_tab_shortcuts)) {
            group.findViewById<MaterialButton>(id)?.apply {
                backgroundTintList = SettingsDrawables.checkedColors(palette.secondaryContainer, Color.TRANSPARENT)
                setTextColor(SettingsDrawables.checkedColors(palette.onSecondaryContainer, palette.onSurface))
                strokeColor = ColorStateList.valueOf(palette.outline)
                rippleColor = ColorStateList.valueOf(palette.secondaryContainer)
                cornerRadius = radius
            }
        }
        group.check(if (chosenTab == Tab.SHORTCUTS) R.id.drawer_tab_shortcuts else R.id.drawer_tab_apps)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tab = if (checkedId == R.id.drawer_tab_shortcuts) Tab.SHORTCUTS else Tab.APPS
            if (tab == chosenTab) return@addOnButtonCheckedListener
            chosenTab = tab
            showItems(allItems)
        }
    }

    /** Fills the grid with what the current tab holds, and shows the tabs only when they split something. */
    private fun showItems(items: List<AppDrawerItem>) {
        allItems = items
        val shown = AppDrawerTabsPolicy.tabsShown(items)
        tabGroup?.visibility = if (shown) View.VISIBLE else View.GONE
        val tab = AppDrawerTabsPolicy.effectiveTab(chosenTab, items)
        if (tab != chosenTab) {
            chosenTab = tab
            tabGroup?.check(R.id.drawer_tab_apps)
        }
        val visible = AppDrawerTabsPolicy.itemsFor(tab, items)
        adapter?.submitList(visible)
        emptyView?.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun closeDrawer() {
        if (dialog != null) {
            try {
                dismissAllowingStateLoss()
            } catch (_: Exception) {
                dismiss()
            }
        } else {
            try {
                if (!findNavController().popBackStack()) {
                    dismissAllowingStateLoss()
                }
            } catch (_: Exception) {
                try {
                    dismissAllowingStateLoss()
                } catch (_: Exception) {}
            }
        }
    }

    private fun warmupIcons(apps: List<AppDrawerItem>) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context?.applicationContext ?: return@launch
            for (item in apps.take(40)) {
                if (AppDrawerCache.iconCache.get(item.iconKey) != null) continue
                val icon = iconFor(ctx, item) ?: continue
                AppDrawerCache.iconCache.put(item.iconKey, icon)
            }
        }
    }

    private fun pickFavorite(slot: Int, item: AppDrawerItem) {
        val settings = App.provide(requireContext()).settings
        settings.homeFavoriteApps =
            FavoriteAppsPolicy.place(settings.homeFavoriteApps, slot, item.favoriteTarget())
        closeDrawer()
    }

    private fun showAddToFavorites(anchor: View, item: AppDrawerItem) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(R.string.home_add_to_favorites).setOnMenuItemClickListener {
                val settings = App.provide(requireContext()).settings
                val updated = FavoriteAppsPolicy.add(settings.homeFavoriteApps, item.favoriteTarget())
                if (updated == null) {
                    ToastUtils.showToast(requireContext(), R.string.home_favorites_full, Toast.LENGTH_LONG)
                } else {
                    settings.homeFavoriteApps = updated
                }
                true
            }
            show()
        }
    }

    private fun launchApp(item: AppDrawerItem) {
        // A shortcut that no longer resolves falls through to the app itself.
        val shortcutId = item.shortcutId
        if (shortcutId != null &&
            LauncherShortcuts.launch(requireContext(), item.packageName, shortcutId)) {
            closeDrawer()
            return
        }

        val comp = ComponentName(item.packageName, item.activityName)
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = comp
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        var launched = false
        try {
            startActivity(launchIntent)
            launched = true
        } catch (e: Exception) {
            AppLog.w(TAG, "Direct launch failed for $comp, trying getLaunchIntentForPackage fallback", e)
            launched = launchPackage(requireContext(), item.packageName)
        }

        if (launched) {
            closeDrawer()
        }
    }
}

class AppDrawerAdapter(
    private var items: List<AppDrawerItem>,
    private val coroutineScope: CoroutineScope,
    private val onItemClick: (AppDrawerItem) -> Unit,
    private val onItemLongClick: (View, AppDrawerItem) -> Unit
) : RecyclerView.Adapter<AppDrawerAdapter.ViewHolder>() {

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newItems: List<AppDrawerItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_drawer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
        private val ivBadge: ImageView = itemView.findViewById(R.id.iv_shortcut_badge)
        private val tvName: TextView = itemView.findViewById(R.id.tv_app_name)

        fun bind(item: AppDrawerItem) {
            tvName.text = item.label
            bindBadge(item)
            val itemKey = item.iconKey
            ivIcon.tag = itemKey

            val cachedIcon = AppDrawerCache.iconCache.get(itemKey)
            if (cachedIcon != null) {
                ivIcon.alpha = 1.0f
                ivIcon.setImageDrawable(cachedIcon)
            } else {
                ivIcon.setImageDrawable(ContextCompat.getDrawable(itemView.context, R.drawable.ic_apps))
                ivIcon.alpha = 0.4f

                coroutineScope.launch(Dispatchers.IO) {
                    val icon = AppDrawerFragment.iconFor(itemView.context.applicationContext, item)
                    if (icon != null) {
                        AppDrawerCache.iconCache.put(itemKey, icon)
                        withContext(Dispatchers.Main) {
                            if (ivIcon.tag == itemKey) {
                                ivIcon.alpha = 1.0f
                                ivIcon.setImageDrawable(icon)
                            }
                        }
                    }
                }
            }

            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                onItemLongClick(it, item)
                true
            }
        }

        /** The badge is drawn at another size than the app's own tile, so it gets its own copy. */
        private fun ownCopy(icon: Drawable): Drawable = icon.constantState?.newDrawable()?.mutate() ?: icon

        /**
         * A shortcut wears its app's icon, the way Android's own launcher shows them: in the
         * shortcuts tab two "Search" tiles would otherwise give no hint of which app they open.
         */
        private fun bindBadge(item: AppDrawerItem) {
            if (item.shortcutId == null) {
                ivBadge.visibility = View.GONE
                return
            }
            ivBadge.visibility = View.VISIBLE
            ivBadge.contentDescription = item.appLabel
                ?: itemView.context.getString(R.string.home_shortcut_badge)
            val appKey = item.appIconKey
            ivBadge.tag = appKey
            val cached = AppDrawerCache.iconCache.get(appKey)
            if (cached != null) {
                ivBadge.setImageDrawable(ownCopy(cached))
                return
            }
            ivBadge.setImageDrawable(AppCompatResources.getDrawable(itemView.context, R.drawable.ic_shortcut_badge))
            coroutineScope.launch(Dispatchers.IO) {
                val icon = AppDrawerFragment.iconFor(itemView.context.applicationContext, item.copy(shortcutId = null))
                    ?: return@launch
                AppDrawerCache.iconCache.put(appKey, icon)
                withContext(Dispatchers.Main) {
                    if (ivBadge.tag == appKey) ivBadge.setImageDrawable(ownCopy(icon))
                }
            }
        }
    }
}
