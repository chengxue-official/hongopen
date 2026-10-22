package com.houg.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.LanInputServer
import com.houg.tv.net.NetClient
import com.houg.tv.repo.DramaIndex
import com.houg.tv.util.QrCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：内置软键盘 + 大家都在搜 + 搜索历史 三栏，外加跨屏输入（二维码）。
 *
 * 设计取舍：
 *  - **内置软键盘**取代系统 IME：电视遥控器唤不出输入法、老盒子 IME 崩溃是常态，
 *    纯遥控器可打字的键盘是刚需（对齐参考形态）。
 *  - **大家都在搜**：红果没有独立「热词」接口，用官方热播榜的剧名充当热词
 *    （既是真实热门内容，也省一个接口），点击即搜该剧名。
 *  - **跨屏输入**：手机连同一 Wi-Fi 扫码 → 浏览器输入剧名 → 回传电视直接搜索。
 *    比遥控器打字快得多，是「搜长剧名」的主路径。
 *  - 搜索仍保留双通道：本地拼音首字母索引（秒开）+ 服务端接口（补全、修正坏 ID）。
 */
class SearchActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: DramaAdapter
    private lateinit var input: EditText
    private lateinit var progress: View
    private lateinit var statusText: TextView
    private lateinit var panelsRow: View
    private lateinit var resultPanel: View
    private lateinit var hotList: RecyclerView
    private lateinit var hotAdapter: SearchChipAdapter
    private lateinit var historyChips: RecyclerView
    private lateinit var historyAdapter: SearchChipAdapter
    private lateinit var softKeyboard: SoftKeyboard
    private lateinit var recycler: RecyclerView
    /** 跨屏输入二维码弹窗（同一时刻最多一个） */
    private var qrDialog: AlertDialog? = null
    /** 局域网跨屏输入服务（仅本页存活期间运行） */
    private var lanServer: LanInputServer? = null
    /** 搜索代次：快速连续搜索时丢弃过期响应 */
    private var searchGen = 0
    /** 本地片名索引是否已就绪 */
    private var indexReady = false

    /**
     * 当前关键词的**全量候选池**（本地索引 + 服务端补全合并后）。
     *
     * 为什么不在滚动时再请求下一页：红果的搜索接口是三路拼合（建议接口 50 条 +
     * 网页 10 条 + 多季补全），没有 offset/游标参数，服务端根本无法分页。
     * 硬造分页只能反复请求同一批数据再本地切片，纯浪费流量。
     *
     * 所以这里的"向下自动加载"是**展示层分页**：候选一次性取回（上限
     * [POOL_MAX]，约几百条，内存可忽略），滚动到底再从池子里追加下一页。
     * 对用户是同一个手感，对盒子是零额外请求 —— 这就是"往体积小、效率高抄"。
     */
    private val resultPool = ArrayList<NetClient.Drama>()

    /** 池子里已展示的条数（滚动加载的游标） */
    private var shownCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.search_input)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.status_text)
        panelsRow = findViewById(R.id.panels_row)
        resultPanel = findViewById(R.id.result_panel)
        hotList = findViewById(R.id.hot_list)
        historyChips = findViewById(R.id.history_chips)
        softKeyboard = findViewById(R.id.soft_keyboard)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        this.recycler = recycler

        adapter = DramaAdapter { drama -> openDetail(drama) }
        recycler.layoutManager = GridLayoutManager(this, adaptiveGridColumns(this, 150, 2, 10))
        recycler.adapter = adapter
        // 滚到底部自动续取下一页（展示层分页，见 resultPool 的说明）
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || shownCount >= resultPool.size) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 1) presentNextPage()
            }
        })

        // 搜索记录：自动换行展示，遥控器可直接导航到每条
        historyAdapter = SearchChipAdapter(
            onClick = { kw -> applyKeyword(kw) },
            onLongClick = { kw ->
                App.searchHistory.remove(kw)
                renderSearchHistory()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
        )
        // ⚠️ 列数必须按「本面板的实际宽度」算，不能用整屏宽。
        // 三栏布局下历史面板只占屏幕约 1/3（960dp 屏 → 约 290dp 宽），
        // 旧代码用 adaptiveGridColumns(this, 130, 2, 8) 按整屏宽算出 7 列，
        // 每列仅约 40dp，连一个关键词胶囊都放不下 —— 文字被 ellipsize 成省略号，
        // 用户于是以为「搜索历史没被记录」（其实磁盘里存着，只是渲染不出来）。
        // 先给个保守列数，待面板完成布局后按实际宽度重算。
        historyChips.layoutManager = GridLayoutManager(this, CHIP_COLS_MIN)
        historyChips.adapter = historyAdapter
        historyChips.post { applyHistoryColumns() }

        // 大家都在搜：竖向单列列表（目标形态是逐行铺开，遥控器上下移动即可）
        hotAdapter = SearchChipAdapter(
            onClick = { kw -> applyKeyword(kw) }
        )
        hotList.layoutManager = LinearLayoutManager(this)
        hotList.adapter = hotAdapter

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_do_search).setOnClickListener { doSearch() }
        findViewById<View>(R.id.btn_lan_input).setOnClickListener { showQrInput() }
        findViewById<View>(R.id.btn_clear_history).setOnClickListener { confirmClearHistory() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        // 内置键盘：点键把字符追加到输入框；功能键走删除/清空/搜索
        softKeyboard.setOnKey { c -> appendChar(c) }
        softKeyboard.setOnCmd { cmd ->
            when (cmd) {
                SoftKeyboard.Cmd.BACKSPACE -> backspace()
                SoftKeyboard.Cmd.CLEAR -> {
                    input.setText("")
                    input.setSelection(0)
                }
                SoftKeyboard.Cmd.SEARCH -> doSearch()
            }
        }

        // 先读本地索引；若缺失则后台重建
        val cached = DramaIndex.load(this)
        indexReady = cached.isNotEmpty()
        if (!indexReady) {
            rebuildIndexInBackground()
        }

        renderSearchHistory()
        loadHotWords()

        // 默认焦点给键盘首键（遥控器进页面即可直接打字），而不是输入框
        softKeyboard.focusFirstKey()
    }

    /* ==================== 输入辅助 ==================== */

    /** 键盘输入的字符追加到输入框末尾（无论焦点在哪，都以遥控器输入为准）。 */
    private fun appendChar(c: Char) {
        val cur = input.text.toString()
        val next = cur + c
        input.setText(next)
        input.setSelection(next.length)
    }

    private fun backspace() {
        val cur = input.text.toString()
        if (cur.isEmpty()) return
        val next = cur.substring(0, cur.length - 1)
        input.setText(next)
        input.setSelection(next.length)
    }

    /** 用给定关键词填充输入框并立即搜索（历史/热词共用）。 */
    private fun applyKeyword(kw: String) {
        input.setText(kw)
        input.setSelection(kw.length)
        doSearch()
    }

    /* ==================== 大家都在搜 ==================== */

    /**
     * 热词取自红果官方「热播榜」（网页 /rank/hot-drama，免签名）。
     * 只取前 [HOT_MAX] 条剧名——榜单本身是热门内容，比伪造热词更有意义。
     * 失败静默（热词是锦上添花，不该给用户报错）。
     */
    private fun loadHotWords() {
        scope.launch {
            val words = try {
                withContext(Dispatchers.IO) {
                    val board = NetClient.RANK_BOARDS.firstOrNull() ?: return@withContext emptyList()
                    App.netClient.ranking(board, 1).items
                        .map { it.drama.title }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(HOT_MAX)
                }
            } catch (e: Exception) {
                emptyList()
            }
            hotAdapter.submit(words)
        }
    }

    /* ==================== 搜索记录 ==================== */

    /**
     * 按搜索记录面板的**实际宽度**重算网格列数。
     *
     * 三栏布局下该面板只有约 1/3 屏宽，若沿用整屏宽计算列数会导致每列过窄、
     * 关键词被省略号吞掉（用户误以为历史没记录）。这里以「每个胶囊至少
     * [CHIP_MIN_WIDTH_DP]」为下限反推列数，并夹在 [CHIP_COLS_MIN]~[CHIP_COLS_MAX]。
     * 布局尚未完成（width=0）时保持原列数不动，等下一次 post 再算。
     */
    private fun applyHistoryColumns() {
        val w = historyChips.width
        if (w <= 0) return
        val cols = (w / dp(CHIP_MIN_WIDTH_DP)).coerceIn(CHIP_COLS_MIN, CHIP_COLS_MAX)
        val lm = historyChips.layoutManager as? GridLayoutManager
        if (lm != null && lm.spanCount == cols) return
        historyChips.layoutManager = GridLayoutManager(this, cols)
    }

    /** dp → px。 */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** 渲染搜索记录胶囊（最多展示 CHIP_MAX 条，避免铺满屏幕）。 */
    private fun renderSearchHistory() {
        val list = App.searchHistory.list()
        historyAdapter.submit(list.take(CHIP_MAX))
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear))
            .setMessage(R.string.clear_search_history_confirm)
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                App.searchHistory.clear()
                renderSearchHistory()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ==================== 跨屏输入（二维码） ==================== */

    /**
     * 弹出二维码：电视起局域网输入服务，手机扫码直达输入页。
     * 服务随页面销毁关闭；每次点开重启一个新的随机端口（避免残留服务被扫到）。
     */
    private fun showQrInput() {
        val server = LanInputServer { keyword ->
            dismissQrDialog()
            if (keyword.isNotEmpty()) applyKeyword(keyword)
        }
        val port = server.start()
        val address = server.localAddress()
        if (port == 0 || address == null) {
            server.stop()
            Toast.makeText(this, R.string.lan_input_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        // 换服务时关掉上一个，避免端口泄漏
        lanServer?.stop()
        lanServer = server

        val url = "http://$address:$port"
        val view = layoutInflater.inflate(R.layout.dialog_qr_input, null)
        val bitmap = QrCode.encode(url, QR_SIZE_PX)
        if (bitmap != null) {
            view.findViewById<ImageView>(R.id.qr_image).setImageBitmap(bitmap)
        }
        view.findViewById<TextView>(R.id.qr_url)
            .text = getString(R.string.scan_url_hint, url)

        qrDialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        qrDialog?.setOnDismissListener { stopLanInput() }
        view.findViewById<View>(R.id.qr_close).setOnClickListener { dismissQrDialog() }
        qrDialog?.show()
    }

    private fun dismissQrDialog() {
        qrDialog?.dismiss()
        qrDialog = null
    }

    private fun stopLanInput() {
        lanServer?.stop()
        lanServer = null
    }

    /**
     * 后台重建片名索引（首次进入且无本地缓存时）。
     *
     * 不动 progress/statusText：它们属于结果面板，索引重建时三栏才是可见的；
     * 若在这里无条件隐藏转圈，会把「重建期间用户恰好发起的搜索」的进度也吞掉。
     * 索引没好时搜索走纯服务端，不阻塞用户。
     */
    private fun rebuildIndexInBackground() {
        scope.launch {
            try {
                DramaIndex.rebuild(this@SearchActivity, App.netClient)
                indexReady = true
            } catch (e: Exception) {
                indexReady = false
            }
        }
    }

    /* ==================== 结果分页展示 ==================== */

    /**
     * 重设候选池并展示首页。
     *
     * 每批 [PAGE_SIZE] 条：比一屏能显示的数量略多，保证「首屏不满」——否则
     * RecyclerView 无法滚动 → 触发不了 onScrolled → 用户永远加载不到下一批。
     */
    private fun resetResults(list: List<NetClient.Drama>) {
        resultPool.clear()
        resultPool.addAll(list)
        shownCount = 0
        presentNextPage()
        // 极端情况（单条结果占满一整屏、或列表高度不足）仍要能继续追加，
        // 不能只依赖滚动事件。
        recycler.post { fillIfNotScrollable() }
    }

    /** 从池子里追加一批到列表末尾。 */
    private fun presentNextPage() {
        if (shownCount >= resultPool.size) return
        val end = minOf(shownCount + PAGE_SIZE, resultPool.size)
        val slice = ArrayList(resultPool.subList(shownCount, end))
        if (shownCount == 0) adapter.submit(slice) else adapter.append(slice)
        shownCount = end
    }

    /** 内容还不够一屏（不可上下滚动）时继续追加，直到可滚动或池子取尽。 */
    private fun fillIfNotScrollable() {
        if (shownCount >= resultPool.size) return
        if (recycler.canScrollVertically(1) || recycler.canScrollVertically(-1)) return
        presentNextPage()
        recycler.post { fillIfNotScrollable() }
    }

    /* ==================== 搜索 ==================== */

    private fun doSearch() {
        val keyword = input.text.toString().trim()
        if (keyword.isEmpty()) return
        // 记入搜索历史（去重上浮），并刷新胶囊区
        App.searchHistory.add(keyword)
        renderSearchHistory()
        input.clearFocus()

        searchGen++
        val gen = searchGen
        panelsRow.visibility = View.GONE
        resultPanel.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        statusText.visibility = View.GONE
        resultPool.clear()
        shownCount = 0
        adapter.submit(emptyList())

        scope.launch {
            try {
                // 本地首字母/标题匹配（索引已就绪时先出结果，保证秒开）
                val local = if (indexReady) {
                    withContext(Dispatchers.IO) { DramaIndex.search(keyword, POOL_MAX) }
                } else emptyList()
                val localDramas = local.map { e ->
                    NetClient.Drama.minimal(e.id, e.title, e.cover)
                }
                if (gen == searchGen && localDramas.isNotEmpty()) {
                    resetResults(localDramas)
                }

                // 服务端搜索兜底/补全：本地索引来自分类翻页（每分类有页数上限），
                // 排在截断点之后的剧会缺失（例：多季剧缺个别季）。因此本地命中后
                // 仍查一次服务端，用更全的数据合并修正。
                val remote = withContext(Dispatchers.IO) {
                    App.netClient.search(keyword)
                }
                if (gen != searchGen) return@launch
                val merged = mergeResults(localDramas, remote)
                resetResults(merged)
                if (merged.isEmpty()) {
                    statusText.text = getString(R.string.empty)
                    statusText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                if (gen != searchGen) return@launch
                if (adapter.itemCount == 0) {
                    statusText.text = getString(R.string.load_failed)
                    statusText.visibility = View.VISIBLE
                }
            } finally {
                if (gen == searchGen) progress.visibility = View.GONE
            }
        }
    }

    /**
     * 合并本地与服务端结果：本地序优先，服务端新增的排在后面。
     * 去重按 id 和归一化标题双键：
     * - 同 id：用服务端更全的数据替换（本地 minimal 只有 id/title/cover）；
     * - 同标题不同 id：保留服务端那条（本地索引的 id 可能是旧版精度 bug
     *   写入磁盘的坏 ID，点进去必然 404，用户看到的「重复的第十季，
     *   一个连不上一个能看」即此因）。
     */
    private fun mergeResults(
        local: List<NetClient.Drama>,
        remote: List<NetClient.Drama>,
    ): List<NetClient.Drama> {
        if (local.isEmpty()) return remote
        if (remote.isEmpty()) return local
        val remoteById = HashMap<String, NetClient.Drama>()
        val remoteByTitle = HashMap<String, NetClient.Drama>()
        for (d in remote) {
            remoteById[d.id] = d
            remoteByTitle[normKey(d.title)] = d
        }
        val seenIds = HashSet<String>()
        val seenTitles = HashSet<String>()
        val result = ArrayList<NetClient.Drama>(local.size + remote.size)
        for (d in local) {
            val tk = normKey(d.title)
            val better = remoteByTitle[tk] ?: remoteById[d.id] ?: d
            if (!seenIds.add(better.id)) continue
            if (!seenTitles.add(tk)) continue
            result.add(better)
        }
        for (d in remote) {
            if (!seenIds.add(d.id)) continue
            if (!seenTitles.add(normKey(d.title))) continue
            result.add(d)
        }
        return result
    }

    /** 标题归一化去重键：去空白与常见标点、全角转半角、转小写。 */
    private fun normKey(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val v = c.code
            var ch = c
            if (v in 0xFF01..0xFF5E) ch = (v - 0xFEE0).toChar()
            if (ch == '　') continue
            if (ch.isWhitespace()) continue
            if (ch in "《》·_-：:，,！!？?\"'“”‘’") continue
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    private fun openDetail(drama: NetClient.Drama) {
        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(DetailActivity.EXTRA_SERIES_ID, drama.id)
        intent.putExtra(DetailActivity.EXTRA_TITLE, drama.title)
        intent.putExtra(DetailActivity.EXTRA_COVER, drama.cover)
        startActivity(intent)
    }

    /**
     * 返回键优先「退回三栏」而不是直接退出：
     * 看完搜索结果想改关键词时，一步回键盘比重新进搜索页顺手。
     * 焦点仍落键盘首键（与进页面时一致），遥控器可继续上下键改词。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && resultPanel.visibility == View.VISIBLE) {
            resultPanel.visibility = View.GONE
            panelsRow.visibility = View.VISIBLE
            // 结果池跟着结果面板一起丢弃：留着只会在下次搜索时和新结果混在一起
            resultPool.clear()
            shownCount = 0
            adapter.submit(emptyList())
            softKeyboard.focusFirstKey()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        dismissQrDialog()
        stopLanInput()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** 搜索记录最多展示的胶囊数（超出需点「清空」管理，避免铺满屏幕） */
        const val CHIP_MAX = 12

        /** 热词条数（榜单前 N 条剧名，够填满中栏即可） */
        const val HOT_MAX = 10

        /** 二维码位图边长（dp 换算前的 px 基准，实际展示 220dp） */
        const val QR_SIZE_PX = 660

        /** 搜索记录胶囊最小宽度（dp），列数按面板实际宽度反推 */
        const val CHIP_MIN_WIDTH_DP = 110

        /** 搜索记录网格最小/最大列数（三栏布局下历史面板仅约 1/3 屏宽） */
        const val CHIP_COLS_MIN = 1
        const val CHIP_COLS_MAX = 4

        /** 结果每次追加的条数（略大于一屏，保证首屏可滚动，从而能触发续取） */
        const val PAGE_SIZE = 24

        /**
         * 候选池上限：本地索引是一次内存扫描，取多少条几乎无成本；
         * 300 条足够覆盖"热门关键词"的全部结果，也避免极端关键词把池子撑爆。
         */
        const val POOL_MAX = 300
    }
}
