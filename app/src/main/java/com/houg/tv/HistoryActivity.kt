package com.houg.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.data.WatchHistory

/**
 * 观看历史 / 我的收藏 双 Tab 页。
 * 从首页「历史」「收藏」按钮分别进入对应 Tab；页内可左右切换。
 * 历史：显示上次看到第几集 + 集内进度，点击进入详情页自动续播。
 * 收藏：用户主动收藏的剧；长按取消收藏。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter
    private lateinit var tabHistory: TextView
    private lateinit var tabFavorite: TextView
    private lateinit var tabLater: TextView
    private lateinit var btnClear: TextView
    private var currentTab = TAB_HISTORY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        tabHistory = findViewById(R.id.tab_history)
        tabFavorite = findViewById(R.id.tab_favorite)
        tabLater = findViewById(R.id.tab_later)
        btnClear = findViewById(R.id.btn_clear)

        tabHistory.setOnClickListener { switchTab(TAB_HISTORY) }
        tabFavorite.setOnClickListener { switchTab(TAB_FAVORITE) }
        tabLater.setOnClickListener { switchTab(TAB_LATER) }
        btnClear.setOnClickListener { confirmClear() }

        adapter = HistoryAdapter(
            onItemClick = { entry -> openDetail(entry) },
            onLongClick = { entry -> confirmRemove(entry) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // 首次进入：清空按钮不抢遥控焦点
        btnClear.isFocusable = true

        switchTab(intent.getIntExtra(EXTRA_TAB, TAB_HISTORY))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        tabHistory.isSelected = tab == TAB_HISTORY
        tabFavorite.isSelected = tab == TAB_FAVORITE
        tabLater.isSelected = tab == TAB_LATER
        refresh()
    }

    private fun listOfTab(): List<WatchHistory.Entry> = when (currentTab) {
        TAB_FAVORITE -> App.watchHistory.favorites()
        TAB_LATER -> App.watchHistory.later()
        else -> App.watchHistory.list()
    }

    private fun refresh() {
        val list = listOfTab()
        // 三个 Tab 里都没有观看进度，列表第二行要按 Tab 换文案
        adapter.emptyLabelRes = when (currentTab) {
            TAB_LATER -> R.string.later_added_btn
            else -> R.string.favorited
        }
        adapter.submit(list)
        val statusText = findViewById<TextView>(R.id.status_text)
        if (list.isEmpty()) {
            statusText.text = getString(
                when (currentTab) {
                    TAB_FAVORITE -> R.string.empty_favorite
                    TAB_LATER -> R.string.empty_later
                    else -> R.string.empty_history
                }
            )
            statusText.visibility = View.VISIBLE
            btnClear.visibility = View.GONE
        } else {
            statusText.visibility = View.GONE
            btnClear.visibility = View.VISIBLE
        }
    }

    private fun openDetail(entry: WatchHistory.Entry) {
        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(DetailActivity.EXTRA_SERIES_ID, entry.seriesId)
        intent.putExtra(DetailActivity.EXTRA_TITLE, entry.title)
        intent.putExtra(DetailActivity.EXTRA_COVER, entry.cover)
        startActivity(intent)
    }

    /** 长按条目：历史里是移除该条，收藏/稍后看里是对应的取消操作。 */
    private fun confirmRemove(entry: WatchHistory.Entry) {
        when (currentTab) {
            TAB_FAVORITE -> AlertDialog.Builder(this)
                .setTitle(getString(R.string.nav_favorite))
                .setMessage("确定取消收藏「${entry.title}」吗？")
                .setPositiveButton("取消收藏") { _, _ ->
                    App.watchHistory.removeFavorite(entry.seriesId)
                    refresh()
                    Toast.makeText(this, R.string.favorite_removed, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()

            TAB_LATER -> AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_later))
                .setMessage("确定把「${entry.title}」移出稍后看吗？")
                .setPositiveButton("移出") { _, _ ->
                    App.watchHistory.removeLater(entry.seriesId)
                    refresh()
                    Toast.makeText(this, R.string.later_removed, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()

            else -> AlertDialog.Builder(this)
                .setTitle("移除记录")
                .setMessage("确定从观看历史移除「${entry.title}」吗？")
                .setPositiveButton("移除") { _, _ ->
                    App.watchHistory.remove(entry.seriesId)
                    refresh()
                    Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 清空当前 Tab 的全部内容（三个 Tab 各清各的）。 */
    private fun confirmClear() {
        val msg = when (currentTab) {
            TAB_FAVORITE -> "确定清空全部收藏吗？"
            TAB_LATER -> "确定清空稍后看吗？"
            else -> getString(R.string.clear_history_confirm)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                // 批量清空（一次性落盘，避免逐条 loadAll+saveAll 造成主线程抖动）
                when (currentTab) {
                    TAB_FAVORITE -> App.watchHistory.clearFavorites()
                    TAB_LATER -> App.watchHistory.clearLater()
                    else -> App.watchHistory.clearHistory()
                }
                refresh()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HISTORY = 0
        const val TAB_FAVORITE = 1
        const val TAB_LATER = 2
    }
}
