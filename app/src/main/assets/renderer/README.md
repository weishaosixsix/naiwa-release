# renderer 目录说明

这里放游戏前端运行所需的文件。仓库里只保留了本项目和 SDK 模拟层相关的部分，
**游戏客户端本体不在仓库内**，版权归原开发商，需要你自己准备。

仓库内已包含：

| 文件 | 说明 |
| --- | --- |
| `index.html` | 页面入口，负责按顺序加载补丁与引擎 |
| `hsdk-mock.js` | Hortor SDK 浏览器端模拟层 |
| `patch.js` | 运行期补丁 |
| `cocos-bundle-patch.js` | Cocos bundle 远程加载补丁 |
| `naiwa-uifix.css` | 修正 body flex 布局，避免脚本面板被挤出屏幕 |

需要你自行补齐（从官方客户端提取）：

```
cocos2d-js-min.<hash>.js     Cocos 引擎
main.<hash>.js               游戏主入口
game-defines.<hash>.js       游戏配置
vers.<hash>.js               资源版本清单
style-mobile.<hash>.css      样式
splash.<hash>.png            启动图
favicon.<hash>.ico
assets/  src/                资源与脚本目录
```

文件名里的 `<hash>` 会随游戏版本变化，`index.html` 里的引用要跟着改。

缺少这些文件时应用能装能启动，但游戏页面会白屏。
