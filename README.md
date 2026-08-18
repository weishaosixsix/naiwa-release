# 再攀之王

咸鱼之王（xyzw）玩家自制的安卓多开助手。完全免费，源码公开，需要的自取。

> **本项目由玩家个人制作，与游戏官方没有任何关系，不是官方客户端。**
> 全程免费，不收费、不卖卡密、不限次数。若有人向你收费，那是诈骗，请索要退款。

## 这是什么

把咸鱼之王的网页版套进安卓应用里，解决多号玩家的两个麻烦：多开、脚本管理。

- **多开**：同时登录多个账号，支持标签页和一屏多窗口两种模式，未激活的账号在后台挂着不掉线
- **账号导入**：微信扫码、手机验证码、`.bin` 文件三种方式；导入一个 bin 可以反查该账号下的全部区服，自己挑要哪个
- **脚本管理**：内置脚本一键开关，也能导入自己的 `.js`；兼容油猴 `GM_*` API
- **自动更新**：启动时检查新版，走加速通道下载（国内直连 GitHub 太慢）

## 下载

装好即用，不需要额外配置。

**[前往 Releases 下载最新版](../../releases/latest)**

安装时系统可能提示「未知来源应用」，允许即可。覆盖安装不会丢账号和脚本设置。

## 从源码编译

仓库里**不含游戏客户端文件和脚本**，那些版权归原作者，得自己准备。缺了它们
应用照样能编译安装，只是游戏页面会白屏。

需要 JDK 17 和 Android SDK 34。

```bash
git clone https://github.com/gterryd/naiwa-release.git
cd naiwa-release
# local.properties 写上 SDK 路径，例如 sdk.dir=D:\\SDK
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/`。

补齐运行期文件的说明见 [renderer/README.md](app/src/main/assets/renderer/README.md)
和 [scripts/README.md](app/src/main/assets/scripts/README.md)。

## 技术实现

游戏跑在 `WebView` 里，本地起一个 HTTP 服务提供资源。几个稍微费劲的地方：

| 模块 | 做了什么 |
| --- | --- |
| `importer/CodeBookCrypto.kt` | 还原游戏 SDK 的登录加密：JSON → Base64 → XOR → Base64 |
| `bon/XyCrypto.kt`、`bon/Lz4.kt` | `.bin` 的两种封装格式，`authuser` 只认 `pl`（LZ4 帧） |
| `bon/BonCodec.kt` | 游戏自用的二进制序列化格式 |
| `core/InjectScripts.kt` | 脚本注入、油猴 API 兼容层、`vh` 单位修正 |
| `core/ScriptCache.kt` | 大脚本走 `<script src>` 加载，绕开 `evaluateJavascript` 的 1MB 上限 |
| `core/UpdateChecker.kt` | 查 GitHub 最新版，下载多镜像兜底 |

踩过的坑记在代码注释里，比如 `100vh` 在 WebView 里会算成 `0px`
（`useWideViewPort` 导致），面板会塌成一条线。

## 免责声明

自用工具，按现状提供，不保证可用性。使用本软件可能违反游戏用户协议，
账号风险自负。不承担因使用本软件产生的任何损失。

不要用于商业用途，不要拿去收费。

## License

代码部分 MIT。

游戏客户端文件、SDK 模拟层、社区脚本各自版权归原作者，不在本许可范围内。
