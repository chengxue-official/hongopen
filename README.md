# hongopen

面向 **Android TV / 电视盒子 / 投影仪** 的第三方短剧客户端，纯原生 Kotlin + Media3，遥控器优先，
支持 Android 5.0（API 21）及以上。

> ⚠️ 本项目与红果短剧、番茄小说及其运营方**无任何关联**，不提供、不存储、不分发任何音视频内容。
> 仅作为 Android TV 客户端与媒体处理的技术示例开源，**请勿用于商业用途**。
> 使用本项目产生的任何后果由使用者自行承担。

## 功能

**浏览**
- 首页分区剧架、分类页（真分页，滚到底继续取）
- 热播榜 / 真人剧榜 / 漫剧榜 / AI 剧榜 四个榜单，支持翻页
- 本地片名索引：全量片单落盘，支持**拼音首字母**秒搜

**搜索**
- 关键词搜索：本地索引先出结果（秒开），再与服务端结果合并补全
- 结果**向下自动加载**：滚到底部继续追加，不用反复点"加载更多"
- 输入框确认键直达结果页

**收藏与记录**
- 本机收藏、最近观看（含断点续播）、稍后看，三个集合互相独立
- 「继续观看」在详情页直达上次看到的那一集
- 进度按「集号 → 集内位置」存储，来回切集不会互相覆盖

**播放**
- 清晰度上限 480P / 720P / 1080P（默认自动，按设备能力选）
- 0.75× ~ 2× 倍速、三种画面模式（适应 / 拉伸 / 裁切）
- 自动连播；**末集播完给「下一部」推荐**，不自动退出
- 硬解默认，可切软解（花屏 / 绿屏 / 音画不同步时救急）
- **定时停止**：按分钟（墙钟计时）或按集数，到点暂停并落盘进度

**按键自定义**
- 上 / 下 / 左 / 右 / 菜单 / 确定 六个键各自可改（播放暂停、快进退、切集、弹幕、倍速、选集……）
- 一键恢复默认；**长按菜单键永远能打开菜单**，改键不会把自己锁在播放页

**弹幕**
- 全屏滚动 / 只有上方 / 隐藏 三档
- 字号（小 / 标准 / 大）与透明度（100 / 80 / 60 / 40%）可调
- 自绘 Canvas，隐藏档直接 `GONE`，不白占重绘循环

**其它**
- 手机辅助：电视出二维码，手机浏览器输入搜索词，走局域网回传（用完即关）
- 应用内更新

## 技术要点

### 不走 MediaDrm，自己解 CENC

片源是 HEVC + CENC（`cenc`），部分老盒子 Widevine 等级不足，走 `MediaDrm` 直接被拒。本项目的链路是：

1. 设备注册拿 `device_id` / `install_id`（非登录态起播的根）
2. 请求播放模型，取 `video_list` 里不超过上限的最高一档
3. 解 `main_url`：`key_seed` 经 `SHA-512(seed ‖ 常量)` 取前 16 字节作 AES-CBC 密钥
4. 下载 MP4，解析 `moov/trak/stbl/saiz/saio/senc`，得到每条样本的偏移、IV 与子样本表，`AES-CTR` 就地解密
5. `sinf` → `free`，remux 成明文 MP4 本地播放，交给系统硬解

好处是解码完全走厂商 MediaCodec，中低端盒子的 HEVC 硬解才跑得动；代价是要自己维护 MP4 盒子解析。

### 请求签名

接口要求 `x-gorgon` + `x-helios` + `x-medusa` **三件套齐备**，缺一个会返回 HTTP 200 但 body 为空（0 字节），
极易误判成算法错误。签名实现见 `app/src/main/java/com/houg/tv/sign/`。

## 构建

需要 JDK 17、Android SDK 35、build-tools 36。

```bash
# 1) 指向本机 SDK（不要提交到仓库）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2) 编译
./gradlew assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

国内网络下 `settings.gradle.kts` 已优先使用阿里云镜像，官方源兜底。

## 目录结构

```
app/src/main/java/com/houg/tv/
  MainActivity.kt              首页：分区剧架 + 导航
  SearchActivity.kt            搜索：内置软键盘 + 热词 + 历史 + 结果分页
  DetailActivity.kt            详情：收藏 / 稍后看 / 选集 / 继续观看
  PlayerActivity.kt            播放器：播放、弹幕、定时停止、按键自定义
  HistoryActivity.kt           历史 / 收藏 / 稍后看 三 Tab
  RankActivity.kt              榜单
  DanmakuView.kt               弹幕自绘（滚动 + 上方固定）
  data/WatchHistory.kt         历史 / 收藏 / 稍后看 三集合持久化
  repo/DramaIndex.kt           本地片名索引（拼音首字母）
  net/NetClient.java           接口层
  net/LanInputServer.kt        局域网手机输入
  sign/                        请求签名（gorgon / helios / medusa / argus）
  playback/                    MP4 解析、CENC 解密、remux
  update/                      应用内更新
```

另有 `ARCHITECTURE.md`、`ARCHITECTURE_PLAYBACK.md`、`API_FAILOVER.md` 记录设计取舍与接口降级策略。

## 已知限制

- **无账号体系**：不做手机号登录与云端同步，所有记录只存在本机，卸载即清空
- 弹幕不支持表情图（纯文字，省体积）
- 单套深色主题
- 接口为第三方私有接口，随时可能变更导致不可用；本项目不对此作任何保证

## 许可证

以 **GPL-3.0-only** 发布，全文见 [LICENSE](LICENSE)。

取流协议与媒体解密方案参考了 [pan8664716/HGTV](https://github.com/pan8664716/HGTV)（GPL-3.0-only）
及其上游 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV)（GPL-3.0）。
为消除许可歧义，本项目自愿采用同一许可证。**来源说明、修改声明与第三方依赖清单见 [NOTICE.md](NOTICE.md)。**

简单说：你可以自由使用、修改、再分发，但**分发时必须同样开源、保留版权声明、并附上修改说明**。
