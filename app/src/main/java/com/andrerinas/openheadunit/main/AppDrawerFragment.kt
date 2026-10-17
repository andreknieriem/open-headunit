package com.andrerinas.openheadunit.main

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppDrawerItem(
    val label: String,
    val packageName: String,
    val activityName: String
)

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

            AppDrawerCache.cachedApps = appsList
            return appsList
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppTheme_Fullscreen)
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

        // If list is cached, display immediately without waiting for query
        val cached = AppDrawerCache.cachedApps
        if (cached != null) {
            adapter.submitList(cached)
            progressBar.visibility = View.GONE
            tvEmpty.visibility = if (cached.isEmpty()) View.VISIBLE else View.GONE
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
                adapter.submitList(apps)
                tvEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                warmupIcons(apps)
            }
        }
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
            val pm = context?.applicationContext?.packageManager ?: return@launch
            for (item in apps.take(40)) {
                val key = "${item.packageName}/${item.activityName}"
                if (AppDrawerCache.iconCache.get(key) == null) {
                    try {
                        val icon = pm.getActivityIcon(ComponentName(item.packageName, item.activityName))
                        AppDrawerCache.iconCache.put(key, icon)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun pickFavorite(slot: Int, item: AppDrawerItem) {
        val settings = App.provide(requireContext()).settings
        settings.homeFavoriteApps = FavoriteAppsPolicy.place(settings.homeFavoriteApps, slot, item.packageName)
        closeDrawer()
    }

    private fun showAddToFavorites(anchor: View, item: AppDrawerItem) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(R.string.home_add_to_favorites).setOnMenuItemClickListener {
                val settings = App.provide(requireContext()).settings
                val updated = FavoriteAppsPolicy.add(settings.homeFavoriteApps, item.packageName)
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
        private val tvName: TextView = itemView.findViewById(R.id.tv_app_name)

        fun bind(item: AppDrawerItem) {
            tvName.text = item.label
            val itemKey = "${item.packageName}/${item.activityName}"
            ivIcon.tag = itemKey

            val cachedIcon = AppDrawerCache.iconCache.get(itemKey)
            if (cachedIcon != null) {
                ivIcon.alpha = 1.0f
                ivIcon.setImageDrawable(cachedIcon)
            } else {
                ivIcon.setImageDrawable(ContextCompat.getDrawable(itemView.context, R.drawable.ic_apps))
                ivIcon.alpha = 0.4f

                coroutineScope.launch(Dispatchers.IO) {
                    val pm = itemView.context.applicationContext.packageManager
                    val icon = try {
                        pm.getActivityIcon(ComponentName(item.packageName, item.activityName))
                    } catch (_: Exception) {
                        try {
                            pm.getApplicationIcon(item.packageName)
                        } catch (_: Exception) {
                            null
                        }
                    }

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
    }
}
