# Anthropic 字体接入

这三个 WOFF2 文件由项目用户于 2026-11-11 提供。未修改或转换字体文件。

| 文件 | 用途 | 可变轴 | 大小 |
| --- | --- | --- | --- |
| AnthropicSans-Roman-Web.woff2 | 界面中的英文、数字，含 Element Plus 控件 | wght 300–800；opsz 16–48 | 118,340 字节 |
| AnthropicSerif-Roman-Web.woff2 | EchoFrame 品牌文字 | wght 300–800；opsz 16–48 | 175,356 字节 |
| AnthropicMono-Roman-Web.woff2 | 代码、JSON、Trace ID 等技术内容 | wght 300–800 | 66,056 字节 |

通过 `fonts.css` 注册，由 `main.js` 引入。三种角色分别使用
`--workbench-ui`、`--workbench-display`、`--workbench-mono`，业务组件不要直接写字体文件路径。

三个文件均不包含汉字，中文自动回退到苹方 / 微软雅黑；页面中文标题继续使用 UI 字体栈。
文件仅包含直立体，声明 `font-style: normal`，不冒充斜体文件。
Sans 与 Serif 启用浏览器自动光学字号；字重使用 `font-weight`，不写死 `font-variation-settings`。
字体从本站按需加载，使用 `font-display: swap`，不通过第三方 CDN 加载，也不预加载全部字体。
未使用 Mono 的页面不会仅因注册字体而下载该文件。

## 来源与许可状态

文件元数据版权信息：`Copyright 2025-2026 Anthropic PBC`。
三个文件的许可说明（name ID 13）与许可 URL（name ID 14）均为空，用户未提供单独许可证。
截至接入时未核实到 Anthropic 对这三个文件的官方开源授权；不要将它们标注为 OFL、MIT 或默认视为受本仓库许可证覆盖。
此记录不构成字体的开源授权；确切授权来源及许可证仍待补充。
