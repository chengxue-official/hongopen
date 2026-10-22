# 来源声明与第三方组件

## 1. 与上游项目的关系

本项目（hongopen）**不是** [pan8664716/HGTV](https://github.com/pan8664716/HGTV) 的 fork，
代码由本项目维护者独立实现（Kotlin / Java 原生 Android TV 应用），未复制其源文件。

但必须如实说明：本项目在**取流协议与媒体解密方案**上，参考了对 HGTV 发布 APK 的反编译研读结果，
而 HGTV 以 **GPL-3.0-only** 发布、并派生自 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV)（GPL-3.0）
与 drpys 的 JavaScript 实现 `spider/js/红果果[短].js`。参考到的部分包括：

- 设备注册与播放模型接口的请求形态（`device_register` → `multi_video_model/preload`）；
- 请求签名三件套 `x-gorgon` / `x-helios` / `x-medusa` 的算法结构与参数集合（其中
  `x-helios` / `x-medusa` 的核心实现是对 HGTV 发布包 `E0.G0` 反编译结果的**逐行移植**，
  见 `app/src/main/java/com/houg/tv/sign/MedusaSign.java`：服务端要求三件套齐备，
  缺任意一个会返回 HTTP 200 但 0 字节空体）；
- 媒体地址解密：`key_seed` → `SHA-512(seed ‖ 常量)` 取前 16 字节作 AES-CBC 密钥，以及 `spade_a` 的逐字节还原流程；
- MP4 CENC 样本解析涉及的盒子顺序（`moov/trak/stbl/saiz/saio/senc`）与子样本偏移计算方式。

为避免任何许可歧义，**本项目自愿以 GPL-3.0-only 发布**，与上游保持一致。
`LICENSE` 为 GPL-3.0 官方文本原文，未作改动（取自 https://www.gnu.org/licenses/gpl-3.0.txt ）。

上游著作权归其各自作者；本项目代码著作权归本项目维护者。本项目与红果短剧、番茄小说及其运营方无任何关联。

## 2. 相对参考实现的差异（GPL-3.0 §5(a) 修改声明）

| 维度 | HGTV（参考实现） | 本项目 |
|---|---|---|
| 目标设备 | Android TV / 盒子，minSdk 19 | Android TV / 盒子，**minSdk 21** |
| 播放链路 | Media3 `DataSource` 层直接解密 CENC | **下载 → 自解析 MP4 → AES-CTR 就地解密样本 → remux 明文 mp4 → 本地播放** |
| 弹幕 | 支持表情图（内置 WebP 资源）、四档位置 | 纯文字自绘 Canvas，两档显示（全屏滚动 / 上方固定）+ 隐藏，支持字号与透明度 |
| 账号 | 手机号登录、云端收藏、重装恢复 | **无账号体系**，全部记录仅存本机 `SharedPreferences` |
| 主题 | 多套材质主题 | 单套深色 UI |
| 手机辅助 | 扫码输入 + 记录导入导出 | 局域网 HTTP 输入页（电视出码，手机输入），不做导出导入 |
| 更新 | 应用内更新 | 应用内更新（同思路，独立实现） |

补充说明：

- 本项目**不使用** `MediaDrm`：红果片源为 HEVC + CENC（`cenc`/`cbcs` 混合），部分老设备的 Widevine 等级不足，
  走 `MediaDrm` 会被拒；改为自行解密后交给系统硬解，是其能在低端盒子上跑起来的关键。
- 项目内不含任何 Node.js、JavaScript 引擎或上游 JS 源文件；开发期用于核对的上游参考实现只在本机使用。

## 3. 第三方依赖

| 组件 | 用途 | 许可证 |
|---|---|---|
| [Kotlin 标准库](https://github.com/JetBrains/kotlin) | 语言运行时 | Apache-2.0 |
| [AndroidX core-ktx / appcompat / recyclerview / cardview / swiperefreshlayout](https://github.com/androidx/androidx) | 基础 UI | Apache-2.0 |
| [AndroidX Media3（exoplayer、ui）](https://github.com/androidx/media) | 播放器与画面比例容器 | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) / [Okio](https://github.com/square/okio) | HTTP 客户端 | Apache-2.0 |
| [Glide](https://github.com/bumptech/glide) | 封面加载 | BSD / MIT / Apache-2.0（混合） |
| [GlideHeifDecoder](https://github.com/msisuzney/GlideHeifDecoder) | 封面为 HEIC 时的解码 | Apache-2.0（以该仓库声明为准） |
| [ZXing core](https://github.com/zxing/zxing) | 生成本机二维码 | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 异步任务 | Apache-2.0 |
| org.json | JSON 解析（使用 Android 平台内置实现，不额外引入） | — |

## 4. 商标与内容

「红果」「番茄」等名称、标识及相关内容版权归其权利人所有。本项目仅为第三方客户端的**技术示例**，
不提供、不存储、不分发任何音视频内容，所有内容均来自公开可访问的接口。请勿用于商业用途。
