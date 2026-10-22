# 二次开发指南

## 1. 更换品牌

编辑 `lib/core/constants.dart`：

```dart
static const String appName = 'Primalite';
static const String projectUrl = 'https://github.com/your-name/primalite';
```

Android 应用名在 `android/app/src/main/AndroidManifest.xml` 的 `android:label`，应用 ID 在 `android/app/build.gradle.kts`。iOS / macOS 名称在各自 `Info.plist` / `AppInfo.xcconfig`。

## 2. 增加/修改搜索引擎

`lib/models/search_engine.dart`：

1. 在 `SearchEngineId` 加枚举值。
2. 加一个 `static const SearchEngine`，模板支持 `{query}` 或 `%s`。
3. 在 `fromId` 里返回它。
4. 在 `lib/screens/settings_page.dart` 的引擎选择器会自动读取 `SearchEngineId.values`；`engineLabel()` 里补品牌名。
5. 图标在 `lib/widgets/address_field.dart` 的 `engineIcon()` 中映射。

## 3. 增加语言

1. `lib/models/app_language.dart` 加枚举与系统语言解析。
2. `lib/core/localization.dart` 加一整个 `Map<String, String>`，并在 `_tables` 注册。
3. `lib/app.dart` 的 `supportedLocales` 加对应 Locale。

没有支持的系统语言会自动回退英文，这是产品需求的一部分。

## 4. 状态管理替换为 Riverpod

当前 Provider 层很薄，只需要：

1. 把 `ChangeNotifier` 改为 `StateNotifier` / `Notifier`。
2. `TabsProvider` 的 controller 创建、关闭、清理逻辑保持不变。
3. 把 `ChangeNotifierProvider` 替换为 `ProviderScope`，用 `ref.watch` 读取。

不建议把 `WebViewController` 放进不可变 state 反复 copy；它是有生命周期的原生对象。

## 5. 移动端隐藏工具栏

`lib/providers/tabs_provider.dart` 优先使用原生滚动回调
`setOnScrollPositionChange`，把 `y` 写入 `scrollEvents`：

```dart
await controller.setOnScrollPositionChange((change) {
  if (tabId != _activeId) return;
  _scrollController.add(change.y);
});
```

原生回调不可用时（`_nativeScrollTabs` 未记录该标签）才在页面加载完成后注入
JS 兜底：

```js
window.addEventListener('scroll', ...) // -> PrimaliteScroll.postMessage(y)
```

`MobileBrowserPage` 订阅 `scrollEvents`，累积滚动超过阈值后切换 `_chromeVisible`。
如果新增平台且同时支持两者，请保持“只用一种”，否则每帧会重复上报。

## 6. 桌面 2×2 网格

`DesktopBrowserPage` 使用 `GridView`，每张卡片持有一个 `WebViewWidget`。注意：

- 同一 `WebViewController` 不能同时用于两个 `WebViewWidget`。
- 关闭标签时 controller 会随 tab 从列表移除；无痕标签会异步清理缓存。
- 标签很多时，所有页面的 WebView 都会驻留内存。若目标设备内存紧张，可改成“只渲染当前活动 WebView + 其他标签显示截图/标题”的策略。

## 7. 下载与书签互转（1.1.0 已实现）

图片下载链路：

- `lib/services/image_download_service.dart`：复用 WebView 的 Cookie 与 Referer，
  限制单张 32 MB，校验 Content-Type / magic bytes。
- `lib/services/file_save.dart` + `lib/services/platform_bridge.dart` +
  `android/.../MainActivity.kt`：Android 走 MediaStore（相册 `Pictures/Primalite`），
  API 24–28 写应用目录并触发媒体扫描；桌面端写系统下载目录。
- `lib/providers/downloads_provider.dart` + `lib/screens/downloads_page.dart`：记录列表。
- `lib/services/webview_scripts.dart`：注入页面的长按/右键钩子，通过
  `PrimaliteImages` channel 回传图片地址（documentStart 注入，失败时在
  `onPageFinished` 补注入）。
- `lib/widgets/image_actions.dart`：图片操作面板与“页面图片”列表。

扩展到“任意文件下载”：在 `TabsProvider` 的 `onNavigationRequest` 判断
`Content-Disposition` 不方便，推荐平台侧 `DownloadListener`；拿到 URL 后复用
`FileSaveService.saveText`/新增 `saveStream`，并注意分块写入与进度上报。

书签互转链路：

- `lib/services/bookmark_transfer_service.dart`：纯 Dart 解析/导出，
  支持 Netscape HTML、Chromium JSON、Firefox JSON、纯网址列表。
- `lib/widgets/bookmark_transfer_sheet.dart`：导入预览、去重统计、导出面板。
- 新增格式时在 `parse()` 加分支；导入统一经 `BookmarksProvider.mergeAll`
  按 `comparisonKey` 去重。

## 8. 打包优化

```bash
flutter build apk --release --split-per-abi
```

如需 R8：

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

`proguard-rules.pro` 已保留 WebView `@JavascriptInterface` 与 AndroidX WebKit 规则。启用后务必在真机验证 JS channel、文件选择、下载等边界。

## 9. 输入法与 Android 手势

- 地址栏（`lib/widgets/address_field.dart`）不要关闭 `enableSuggestions`：
  Gboard 的中文/日文候选栏依赖 IME 的 suggestion 模式；也不要聚焦时立刻
  改写 `TextEditingController.value`，否则会破坏组合区（composing region）。
- 提交时先 `clearComposing()` 再导航，避免把未上屏的拼音发给搜索引擎。
- `SystemUi.edgeGestureWidth`（24dp）是系统返回/主页手势的保留区；
  自定义横滑手势必须避开屏幕两侧，否则会与系统手势冲突。
- 返回手势统一在 `BrowserShell` 的 `PopScope` 中处理：键盘 → 网页历史 →
  关闭标签 → 二次返回退出；`AndroidManifest.xml` 需要
  `android:enableOnBackInvokedCallback="true"`。
- 安全区请同时考虑 `MediaQuery.padding` 与 `viewPadding`（手势条）。

### 底部工具栏与沉浸式全屏（1.3.1）

- 开关：`AppSettings.showBottomBar`（默认 `true`），读写走
  `SettingsProvider.isBottomBarVisible / setBottomBarVisible`；UI 在
  `settings_page.dart` 的「通用」分组。
- `MobileBrowserPage` 根据开关同时调整三处，新增界面时不要只改其中一处：
  1. 底部 `MobileBottomBar` 是否渲染；
  2. WebView `Padding.bottom`（关闭时只留安全区，隐藏 chrome 时为 0）；
  3. 开始页 `BrowserHomeContent.padding.bottom` 与错误提示条的底部偏移。
- 全屏：chrome 收起时调用 `SystemUi.enterImmersive()`，恢复时调用
  `SystemUi.exitImmersive()`；所有显隐都走 `_setChromeVisible()`，由
  `_syncImmersive()` 保证系统栏状态一致。新增“打开弹层/跳转页面”的入口时，
  先调用 `_showChrome()`（菜单、标签选择器、引擎选择器已处理）。
- 安全区统一用 `SystemUi.safeInsets(context)`（逐边取 `padding` 与
  `viewPadding` 的较大值），横屏刘海/挖孔、手势条、键盘都不会遮挡内容。
- Android 侧还需要 `styles.xml` 的 `windowLayoutInDisplayCutoutMode=shortEdges`
  与 `enforce*Contrast=false`，否则横屏挖孔区域不可用、透明系统栏会被系统加底色。

## 10. 浏览器标识与快捷链接（1.2.0）

浏览器标识（User-Agent）：

- 预设与字符串集中在 `lib/core/user_agents.dart`（`UserAgentPreset` +
  `UserAgents`），设置模型保存在 `AppSettings.userAgentPreset/customUserAgent`。
- 新增预设：在 enum 加值 → 在 `resolve()` 返回字符串 → 在 `labelKey()` 加本地化
  key → 把值加入 `selectable`（UI 顺序）→ 在 `lib/core/localization.dart` 三语
  表补文案。
- 应用逻辑在 `TabsProvider._createController`（新标签）和
  `TabsProvider.applyUserAgent()`（已打开标签 + 刷新当前页）。切换身份后站点
  需要重新加载才会看到新 UA，因此设置页会调用 `applyUserAgent()`。
- 桌面平台的 WebView2 / WebKitGTK 允许自定义 UA；个别平台可能忽略，代码已捕获
  异常，不影响浏览。

快捷链接：

- 默认站点已移除（1.2.0），数据在 `QuickLinksProvider`（`primalite.quicklinks.v1`）。
- UI 在 `lib/widgets/quick_links_sheet.dart`：管理面板 + 添加/编辑对话框；
  开始页 `home_content.dart` 只读取用户添加的条目。
- 新增字段或排序能力时改 `QuickLink` 模型与 provider，并在管理面板加操作。

## 11. 主题、首页小组件与二级菜单（1.3.0 / 1.3.2）

### 主题色与深色模式

- 颜色工具在 `lib/core/color_utils.dart`（十六进制解析、可读强调色、网站配色推导），
  纯函数、有单元测试。
- 运行时调色板在 `lib/core/app_theme.dart` 的 `AppColors`：`applyAccent()` 会把
  用户选择的颜色换算为浅色/深色两套 `accent` / `accentSoft`。旧代码里的
  `AppColors.accent` 调用点无需改动。
- `AppTheme.light(accent)` / `AppTheme.dark(accent)` 按主题色缓存 `ThemeData`，
  新增主题变体时记得同步 `_build()`。
- 主题设置模型：`AppSettings.themeMode` / `accentValue` / `siteThemeBackground`；
  写入入口在 `SettingsProvider`。
- 新增预设色：改 `ColorUtils.presets` 并在三语表补名称（可选，UI 只显示色块）。

### 背景色跟随网站主题色

- 页面探测脚本在 `lib/services/webview_scripts.dart` 的 `siteTheme`：优先
  `meta[name=theme-color]`（支持 media），其次 `body` / `html` 的背景色，
  返回 `{"color":"#rrggbb","dark":bool}`。
- `TabsProvider._probeSiteTheme()` 在页面完成、SPA 路由变化（320ms 防抖）和
  设置开关变化时执行；结果存 `BrowserTab.siteColor`。
- 展示在 `lib/screens/mobile_browser_page.dart`：`ColorUtils.siteSurface/Border`
  推导工具栏底色与描边，始终保证正文对比度，不直接把网页颜色铺满。
- 如果目标 WebView 不支持 `runJavaScriptReturningResult`，该功能会静默关闭，
  不影响浏览。

### 首页小组件

- 枚举与元数据：`lib/models/home_widget.dart`（`HomeWidgetId` +
  `HomeWidgetCatalog`）。持久化只存枚举名列表，未知值会被丢弃。
- 渲染：`lib/widgets/home_widgets.dart` 每个小组件一个 Widget；新增小组件时
  1）加枚举值 2）在 `labelKey/descriptionKey` 加本地化 key 3）加渲染 Widget
  4）在 `home_content.dart` 的 `_buildWidget()` 加分支。
- 管理页：`lib/screens/home_widgets_page.dart`，已启用列表可拖动排序、开关增减；
  数据入口 `SettingsProvider.setHomeWidgets/toggleHomeWidget/resetHomeWidgets`。
- 时间组件自带 1 秒 Timer，但只在分钟变化时 `setState`，不要改成每秒重建。

### 二级菜单

- 动作枚举：`lib/models/menu_action.dart`；`MenuActions.sanitize()` 保证任何存储
  值都会得到一个完整的动作列表（缺失项按默认顺序补齐）。
- 布局与拖动排序：`lib/widgets/circular_menu.dart`（`MenuLayout.radial` /
  `MenuLayout.rail`）；图标映射在 `lib/widgets/menu_icons.dart`。
- 着色器/模糊：`CircularMenuOverlay.blurSigma`（0 关闭）。模糊走 `BackdropFilter`，
  在 `_buildScrim()` 内按强度同时调淡渐变蒙层；设置值来自
  `AppSettings.menuBlur`（0–30，`MenuBlur` 负责范围与分档标签）。
- 显隐：`AppSettings.hiddenMenuActions` 保存被隐藏动作的枚举名；
  `MenuActions.sanitizeHidden()` 会丢弃未知项并强制保留 `settings`，
  `AppSettings.visibleMenuActions` 是菜单真正渲染的列表。
  设置写入入口 `SettingsProvider.setMenuActionVisible()`。
- 排序持久化：`SettingsProvider.setMenuOrder()`；在菜单里拖动时，
  `MenuActions.mergeOrder()` 会把当前上下文隐藏的动作（例如首页的
  「页面图片」、被用户关掉的功能）保留在原位置。设置页与浏览页共用同一函数。
- 环形菜单会按动作数量自适应：超过 8 项时半径取 `min(width*0.35, 156)`，
  每项宽度按弧长收窄（52–78dp），14 项仍可排布。新增动作时无需改这段逻辑。
- 设置页：`lib/screens/menu_settings_page.dart`
  （布局、模糊滑杆与预览、显隐开关、拖动排序、两个恢复按钮）。
- 新增动作：加枚举值 → 在 `menuActionIcon` 加图标 → 在
  `mobile_browser_page._openMoreMenu()` 的 `entries` 加构造 → 三语表补
  `labelKey` / `descriptionKey` → 决定它是否进入
  `MenuActions.defaultIds`（默认显示）与 `defaultHiddenIds`（默认隐藏）。
  枚举名就是持久化键，已有用户的顺序不会因为新增动作而改变。
- 桌面端「更多」是固定 `PopupMenuButton`（`desktop_browser_page.dart`），
  不受本页显隐设置影响：桌面工具栏已经常驻后退/前进，菜单项也各不相同。

## 12. 发布前检查

- [ ] 替换 `AppConstants.projectUrl`
- [ ] 替换 debug keystore
- [ ] 检查 http 明文策略
- [ ] 在 Android / iOS / Windows / macOS / Linux 至少各跑一次
- [ ] 确认无痕模式清理行为符合你的隐私声明
- [ ] 保留 GPLv3 `LICENSE` 与版权声明
