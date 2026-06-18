# PDF 阅读器架构说明文档

> **项目**: My_PDF_manager  
> **日期**: 2026-06-15  
> **作者**: 齐活林（Qi）· 交付总监

---

## 📋 目录

1. [架构概览](#架构概览)
2. [新架构：连续画布模式](#新架构连续画布模式)
3. [旧架构：单页模式](#旧架构单页模式)
4. [核心组件详解](#核心组件详解)
5. [数据流与交互流程](#数据流与交互流程)
6. [技术决策与优化](#技术决策与优化)
7. [文件清单](#文件清单)

---

## 架构概览

PDF 阅读器当前处于**架构迁移期**，同时存在两套实现：

| 特性 | 旧架构（单页模式） | 新架构（连续画布模式） |
|------|---------------------|------------------------|
| **文件名** | `ReaderScreen.kt` | `ReaderScreenV2.kt` |
| **显示方式** | 单次显示一页 | 连续滚动显示多页 |
| **翻页交互** | 点击按钮 / 左右滑动 | 自由滚动 / 惯性滑动 |
| **缩放** | 不支持 | 支持（ScaleGestureDetector） |
| **缓存策略** | LruCache（5 页） | LruCache（64MB）+ 预渲染 |
| **渲染管理** | 简单渲染 | 优先级队列 + 并发限制 |
| **手势处理** | 基础 | 完整（滚动/缩放/惯性） |

**当前状态**：两套代码共存，`ReaderScreenV2.kt` 是新的连续画布实现，但仍在完善中。

---

## 新架构：连续画布模式

### 设计目标

1. **类 WPS / 福昕阅读器体验**：连续滚动多页 PDF，无需手动翻页
2. **流畅的交互**：支持手势缩放、惯性滚动
3. **内存安全**：LRU 缓存 + 并发限制，避免 OOM
4. **响应式 UI**：基于 Jetpack Compose + ViewModel

---

### 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        UI 层                               │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  ReaderScreenV2.kt (Compose)                     │   │
│  │  - 顶部工具栏（TopAppBar）                        │   │
│  │  - 底部工具栏（ReaderBottomBar）                  │   │
│  │  - 页码指示器（PageNumberBadge）                 │   │
│  │  - AndroidView（嵌入 PdfContinuousView）          │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                           ↓ AndroidView 桥接
┌─────────────────────────────────────────────────────────────┐
│              自定义 View 层                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  PdfContinuousView.kt (View)                      │   │
│  │  - 集成 ContinuousCanvasState                    │   │
│  │  - 集成 PageCacheManager                        │   │
│  │  - 集成 PdfRendererManager                      │   │
│  │  - 集成 ContinuousCanvasGestureHandler          │   │
│  │  - onDraw() 绘制可见页面                        │   │
│  │  - onTouchEvent() 处理手势                     │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                           ↓ 状态观察
┌─────────────────────────────────────────────────────────────┐
│                      ViewModel 层                            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  ReaderViewModel.kt (AndroidViewModel)             │   │
│  │  - 管理 PdfRenderer 生命周期                     │   │
│  │  - 渲染 PDF 页面（全分辨率 Bitmap）             │   │
│  │  - 观察阅读设置（PreferencesManager Flow）       │   │
│  │  - 管理页面状态（currentPage, totalPages）        │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                           ↓ 数据访问
┌─────────────────────────────────────────────────────────────┐
│                      数据层                                  │
│  - PdfFile (数据模型)                                      │
│  - PdfRepository (数据库访问)                               │
│  - PreferencesManager (设置持久化)                          │
└─────────────────────────────────────────────────────────────┘
```

---

### 核心组件

#### 1. **PdfContinuousView**（自定义 View）

**职责**：
- 作为连续画布的**核心视图容器**
- 集成所有管理器（状态、缓存、渲染、手势）
- 负责 `onDraw()` 绘制可见页面
- 负责 `onTouchEvent()` 转发手势事件

**关键方法**：
```kotlin
fun init(pdfRenderer: PdfRenderer, cacheSize: Int)
  // 初始化所有管理器

private fun updateVisiblePages()
  // 计算可见页面并触发渲染

override fun onDraw(canvas: Canvas)
  // 绘制可见页面（从缓存获取 Bitmap）

override fun onTouchEvent(event: MotionEvent): Boolean
  // 转发手势给 ContinuousCanvasGestureHandler

fun scrollToPage(pageIndex: Int, alignTop: Boolean)
  // 滚动到指定页面

fun cleanup()
  // 释放所有资源
```

**状态回调**：
- `onPageRendered: ((Int, Bitmap?) -> Unit)?` - 页面渲染完成
- `onPageChanged: ((Int) -> Unit)?` - 当前页面变化
- `onScrollProgressChanged: ((Float) -> Unit)?` - 滚动进度变化

---

#### 2. **ContinuousCanvasState**（状态管理）

**职责**：
- 计算**每个页面在连续画布中的 Y 坐标**
- 根据 `scrollOffset` 和 `viewportHeight` 计算**可见页面集合**
- 提供 `scrollToPage()` 和 `scrollToProgress()` 滚动控制

**核心数据结构**：
```kotlin
private val pageHeights = mutableMapOf<Int, Int>()       // 页面高度
private val pageYOffsets = mutableMapOf<Int, Int>()    // 页面 Y 坐标
var totalHeight: Int = 0                                 // 连续画布总高度
var scrollOffset: Int = 0                               // 当前滚动偏移量
var visiblePages: Set<Int> = emptySet()                 // 当前可见页面
```

**关键方法**：
```kotlin
private fun calculatePageLayout()
  // 打开每一页获取高度，并计算 Y 坐标

fun updateScrollOffset(newOffset: Int): Boolean
  // 更新滚动偏移量（限制范围）

fun getPageYOffset(pageIndex: Int): Int
  // 获取指定页面的 Y 坐标

fun getTopVisiblePage(): Int
  // 获取当前最顶部的可见页面

fun scrollToPage(pageIndex: Int, alignTop: Boolean)
  // 滚动到指定页面（支持顶部对齐或居中显示）

fun getScrollProgress(): Float
  // 获取滚动进度（0.0 - 1.0）
```

**初始化流程**：
```kotlin
init {
    calculatePageLayout()     // 计算所有页面的 Y 坐标
    totalHeight = calculateTotalHeight()
    scrollOffset = 0
    updateVisiblePages()     // 计算初始可见页面
}
```

---

#### 3. **ContinuousCanvasGestureHandler**（手势处理）

**职责**：
- 处理**缩放手势**（ScaleGestureDetector）
- 处理**拖动手势**（GestureDetector）
- 处理**惯性滚动**（Scroller）
- 处理**单击/双击**（GestureDetector）

**依赖的 Android API**：
- `ScaleGestureDetector` - 双指缩放
- `GestureDetectorCompat` - 滚动、单击、双击、惯性滚动
- `Scroller` - 惯性滚动动画

**回调接口**：
```kotlin
private val onScroll: (Float, Float) -> Unit,    // 滚动回调 (deltaX, deltaY)
private val onScale: (Float, Float, Float) -> Unit, // 缩放回调 (scaleFactor, focusX, focusY)
private val onFling: (Float, Float) -> Unit,      // 惯性滚动回调 (velocityX, velocityY)
private val onSingleTap: (Float, Float) -> Unit,  // 单击回调 (x, y)
private val onDoubleTap: (Float, Float) -> Unit    // 双击回调 (x, y)
```

**关键方法**：
```kotlin
fun onTouchEvent(event: MotionEvent): Boolean
  // 处理触摸事件（转发给 scaleDetector 和 gestureDetector）

fun scrollBy(deltaX: Float, deltaY: Float)
  // 滚动指定距离

fun scrollTo(x: Float, y: Float)
  // 滚动到指定位置

fun computeScroll(): Boolean
  // 计算惯性滚动（在 View 的 computeScroll 中调用）

fun setDimensions(canvasW, canvasH, viewportW, viewportH)
  // 设置画布和视口尺寸（用于限制滚动范围）

fun setScaleRange(min: Float, max: Float)
  // 设置缩放范围（默认 1.0 - 5.0）
```

**缩放逻辑**：
- 当前缩放比例：`scale: Float = 1.0f`
- 最小缩放：`minScale: Float = 1.0f`
- 最大缩放：`maxScale: Float = 5.0f`
- 缩放时保持焦点位置（`scaleFocusX`, `scaleFocusY`）

---

#### 4. **PageCacheManager**（缓存管理）

**职责**：
- 使用 **LRU 缓存**（Android `LruCache`）管理 Bitmap
- 自动回收被移除的 Bitmap（避免内存泄漏）
- 提供缓存命中率统计

**缓存策略**：
- **最大缓存大小**：64MB（可配置）
- **缓存淘汰**：LRU（最近最少使用）
- **自动回收**：`entryRemoved()` 回调中回收 Bitmap

**关键方法**：
```kotlin
fun get(pageIndex: Int): Bitmap?
  // 从缓存中获取 Bitmap（命中率统计）

fun put(pageIndex: Int, bitmap: Bitmap)
  // 将 Bitmap 放入缓存

fun remove(pageIndex: Int): Bitmap?
  // 移除指定页面的 Bitmap

fun clear()
  // 清空缓存（会触发所有 Bitmap 回收）

fun preload(pageIndex: Int, loader: () -> Bitmap?)
  // 预加载页面到缓存（如果不存在）

fun trimTo(keepPages: Set<Int>)
  // 修剪缓存（保留指定页面，移除其他）
```

**统计功能**：
```kotlin
fun getHitRate(): Float       // 获取缓存命中率（0.0 - 1.0）
fun getCurrentSize(): Int     // 获取当前缓存大小（字节）
fun getCacheCount(): Int     // 获取缓存的页面数量
```

---

#### 5. **PdfRendererManager**（渲染管理）

**职责**：
- 使用 **优先级队列** 管理渲染请求
- **并发限制**（Semaphore(2)）避免内存溢出
- 支持**同步渲染**和**异步渲染**

**渲染优先级**：
```kotlin
enum class RenderPriority {
    HIGH,    // 当前可见页面
    NORMAL,  // 相邻页面
    LOW      // 其他页面
}
```

**关键方法**：
```kotlin
suspend fun renderPage(
    pageIndex: Int,
    priority: RenderPriority,
    width: Int,
    height: Int
): Bitmap?
  // 同步渲染（挂起函数，会等待渲染完成）

fun renderPageAsync(
    pageIndex: Int,
    priority: RenderPriority,
    width: Int,
    height: Int,
    onComplete: (Bitmap?) -> Unit
): Job
  // 异步渲染（不等待完成，通过回调返回结果）

fun renderPagesAsync(
    requests: List<RenderRequest>,
    onPageRendered: (Int, Bitmap?) -> Unit
)
  // 批量提交渲染请求（按优先级排序）

fun cancelRender(pageIndex: Int)
  // 取消指定页面的渲染任务

fun cancelAllRenders()
  // 取消所有渲染任务
```

**并发控制**：
- 使用 `Semaphore(2)` 限制同时渲染的任务数为 2
- 渲染任务在 `Dispatchers.IO` 线程池中执行

---

#### 6. **ReaderScreenV2**（Compose UI）

**职责**：
- 使用 `AndroidView` 嵌入 `PdfContinuousView`
- 显示**顶部工具栏**（TopAppBar）
- 显示**底部工具栏**（ReaderBottomBar）
- 显示**页码指示器**（PageNumberBadge）
- 观察 ViewModel 状态（currentPage, totalPages, pdfFile）
- 实现**工具栏自动隐藏**功能

**关键实现**：
```kotlin
@Composable
fun ReaderScreenV2(
    fileId: String,
    onBack: () -> Unit,
    navController: NavController,
    viewModel: ReaderViewModel = viewModel()
) {
    // 1. 观察 ViewModel 状态
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val totalPages by viewModel.totalPages.collectAsStateWithLifecycle()
    val pdfFile by viewModel.pdfFile.collectAsStateWithLifecycle()

    // 2. 初始化 ViewModel
    LaunchedEffect(fileId) {
        viewModel.initialize(fileId)
    }

    // 3. 嵌入 PdfContinuousView
    AndroidView(
        factory = { ctx -> PdfContinuousView(ctx) },
        update = { view ->
            // 当 pdfRenderer 可用时，初始化视图
            val renderer = viewModel.pdfRenderer
            if (renderer != null) {
                view.init(renderer)
            }
        }
    )

    // 4. 顶部工具栏
    if (isToolbarVisible) {
        TopAppBar(
            title = { Text(pdfFile?.name ?: "加载中...") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )
    }

    // 5. 底部工具栏
    if (isToolbarVisible) {
        ReaderBottomBar(...)
    }

    // 6. 页码指示器（工具栏隐藏时显示）
    if (!isToolbarVisible) {
        PageNumberBadge(...)
    }
}
```

**工具栏自动隐藏**：
```kotlin
// 观察设置
val autoHide by viewModel.autoHide.collectAsStateWithLifecycle()
val autoHideDelay by viewModel.autoHideDelay.collectAsStateWithLifecycle()

// 自动隐藏逻辑
LaunchedEffect(isToolbarVisible, autoHide, autoHideDelay) {
    if (isToolbarVisible && autoHide) {
        delay(autoHideDelay * 1000L)
        isToolbarVisible = false
    }
}
```

---

#### 7. **ReaderViewModel**（ViewModel）

**职责**：
- 管理 `PdfRenderer` 生命周期
- 渲染 PDF 页面（全分辨率 Bitmap）
- 观察阅读设置（通过 `PreferencesManager` Flow）
- 管理页面状态（currentPage, totalPages）
- 管理 Bitmap 缓存（LruCache）
- 保存/恢复书签位置

**关键状态**：
```kotlin
private val _pdfFile = MutableStateFlow<PdfFile?>(null)
val pdfFile: StateFlow<PdfFile?>

private val _currentPage = MutableStateFlow(0)
val currentPage: StateFlow<Int>

private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
val currentBitmap: StateFlow<Bitmap?>

private val _totalPages = MutableStateFlow(0)
val totalPages: StateFlow<Int>

// 阅读设置（观察 PreferencesManager Flow）
val pageMode: StateFlow<String>        // "single_page" / "scroll"
val fitMode: StateFlow<String>         // "fit_width" / "fit_height" / "original"
val toolbarMode: StateFlow<String>     // "full" / "page_only" / "hidden"
val autoHide: StateFlow<Boolean>
val autoHideDelay: StateFlow<Int>
val doubleTapZoom: StateFlow<Boolean>
```

**关键方法**：
```kotlin
fun initialize(fileId: String)
  // 初始化阅读器（打开 PdfRenderer，恢复书签）

private suspend fun openPdfRenderer(uri: Uri?)
  // 打开 PdfRenderer 并渲染第一页

fun renderPage(index: Int)
  // 渲染指定页面（带缓存）

suspend fun getPageBitmap(index: Int): Bitmap?
  // 按需渲染指定页面（供滚动模式使用，带并发限制）

fun nextPage() / fun previousPage()
  // 翻页

fun goToPage(index: Int)
  // 跳转到指定页面（带预渲染）

fun setPageMode(mode: String) / setFitMode(mode: String) / ...
  // 切换设置项（同时持久化）

override fun onCleared()
  // 清理资源（保存书签，关闭 PdfRenderer，回收 Bitmap）
```

**缓存策略**：
- 使用 `LruCache<Int, Bitmap>(5)` 缓存最近渲染的 5 页
- `getPageBitmap()` 会检查缓存，命中则直接返回
- 使用 `Semaphore(2)` 限制并发渲染数量

**书签功能**：
```kotlin
private fun saveBookmark()
  // 保存当前页到书签（如果开启了书签记忆）

fun deleteBookmark()
  // 删除当前文件的书签

// 在 onCleared() 中自动调用 saveBookmark()
```

---

### 交互流程

#### 1. 初始化流程

```
用户点击 PDF 文件
    ↓
ReaderScreenV2 接收 fileId
    ↓
LaunchedEffect(fileId) { viewModel.initialize(fileId) }
    ↓
ReaderViewModel.initialize()
    ├─ 从 PdfRepository 获取 PdfFile
    ├─ openPdfRenderer(pdfFile.uri)
    │   ├─ 关闭之前的 PdfRenderer
    │   ├─ 从 Uri 获取 ParcelFileDescriptor
    │   ├─ 创建 PdfRenderer(pfd)
    │   ├─ 更新 _totalPages
    │   └─ renderPage(0) 渲染第一页
    └─ 恢复书签位置（如果开启了书签记忆）
            ↓
PdfContinuousView.init(pdfRenderer)
    ├─ 创建 ContinuousCanvasState（计算页面布局）
    ├─ 创建 PageCacheManager（64MB 缓存）
    ├─ 创建 PdfRendererManager（渲染管理器）
    ├─ 创建 ContinuousCanvasGestureHandler（手势处理）
    └─ updateVisiblePages() → renderVisiblePages()
```

---

#### 2. 滚动流程

```
用户手指在屏幕上滑动
    ↓
PdfContinuousView.onTouchEvent(event)
    ↓
ContinuousCanvasGestureHandler.onTouchEvent(event)
    ↓
GestureDetector.onScroll() 回调
    ↓
onScroll(deltaX, deltaY) 回调
    ↓
ContinuousCanvasState.updateScrollOffset(newOffset)
    ├─ 限制偏移量范围
    └─ updateVisiblePages()
            ↓
PdfContinuousView.updateVisiblePages()
    ├─ 获取新的 visiblePages
    ├─ 回调 onPageChanged(topPage)
    ├─ 回调 onScrollProgressChanged(progress)
    └─ renderVisiblePages()
            ↓
PdfContinuousView.renderVisiblePages()
    ├─ 取消不可见页面的渲染任务
    └─ 提交可见页面的渲染任务
            ↓
PdfRendererManager.renderPageAsync()
    ├─ 检查缓存（命中则直接绘制）
    └─ 缓存未命中 → 提交异步渲染任务
            ↓
渲染完成 → onPageRendered(pageIndex, bitmap)
    ↓
PageCacheManager.put(pageIndex, bitmap)
    ↓
PdfContinuousView.invalidate() → onDraw()
    ↓
绘制可见页面（从缓存获取 Bitmap）
```

---

#### 3. 缩放流程

```
用户双指缩放
    ↓
PdfContinuousView.onTouchEvent(event)
    ↓
ContinuousCanvasGestureHandler.onTouchEvent(event)
    ↓
ScaleGestureDetector.onScale()
    ↓
onScale(scaleFactor, focusX, focusY) 回调
    ↓
更新 scale（限制范围 1.0 - 4.0）
    ↓
重新计算页面布局（根据缩放比例）
    ↓
PdfContinuousView.invalidate() → onDraw()
    ↓
绘制缩放后的页面
```

---

#### 4. 页面渲染流程

```
PdfContinuousView.renderVisiblePages()
    ↓
遍历 visiblePages
    ↓
PageCacheManager.get(pageIndex)
    ↓
缓存命中？
    ├─ 是 → 直接使用 Bitmap 绘制
    └─ 否 → 提交渲染任务
            ↓
PdfRendererManager.renderPageAsync()
    ↓
创建协程（Dispatchers.IO）
    ↓
获取信号量（限制并发）
    ↓
PdfRenderer.openPage(pageIndex)
    ↓
计算渲染尺寸（根据屏幕宽度）
    ↓
Bitmap.createBitmap(width, height)
    ↓
page.render(bitmap, null, null, RENDER_MODE_FOR_DISPLAY)
    ↓
page.close()
    ↓
释放信号量
    ↓
onComplete(bitmap) 回调（切回主线程）
    ↓
PageCacheManager.put(pageIndex, bitmap)
    ↓
PdfContinuousView.invalidate() → onDraw()
    ↓
绘制页面
```

---

### 技术决策与优化

#### 1. 为什么使用 `AndroidView` 嵌入自定义 View？

**原因**：
- Compose 的 `VerticalScroll` + `LazyColumn` 方案在渲染大量 PDF 页面时**性能不佳**（需要频繁重组）
- 自定义 `View` + `Canvas.drawBitmap()` 方案**性能更优**（直接操作 Canvas）
- 可以利用现有的 `PdfRenderer` API（需要 `ParcelFileDescriptor`）

**权衡**：
- ✅ 性能优秀（直接 Canvas 绘制）
- ✅ 内存可控（LRU 缓存 + 并发限制）
- ❌ 需要维护自定义 View（代码复杂度较高）

---

#### 2. 为什么使用 `LruCache` 而不是 `MutableStateFlow`？

**原因**：
- `LruCache` 提供**自动淘汰机制**（最近最少使用）
- 可以精确控制**缓存大小**（64MB）
- 提供 `entryRemoved()` 回调，可以**自动回收 Bitmap**（避免内存泄漏）

**对比**：
| 方案 | 优点 | 缺点 |
|------|------|------|
| `LruCache` | 自动淘汰、内存可控、自动回收 | 需要手动管理 |
| `MutableStateFlow` | 响应式、Compose 友好 | 无自动淘汰、内存不可控 |

---

#### 3. 为什么使用 `Semaphore(2)` 限制并发渲染？

**原因**：
- PDF 渲染是**内存密集型操作**（每页 Bitmap 可能占用几 MB）
- 无限制并发会导致 **OOM（内存溢出）**
- 限制为 2 个并发任务可以**平衡性能和内存**

**测试数据**（假设）：
- 1 页 Bitmap（全分辨率）≈ 2-5 MB
- 2 个并发任务 ≈ 4-10 MB
- 如果无限制并发，可能同时渲染 10+ 页 → 40-50 MB → OOM

---

#### 4. 为什么使用 `PriorityQueue` 管理渲染请求？

**原因**：
- 用户在快速滚动时，会产生**大量渲染请求**
- 如果按请求顺序渲染，**当前可见页面**可能因为排队而过时
- 使用优先级队列，**当前可见页面**优先渲染

**优先级策略**：
- `HIGH` - 当前可见页面（立即渲染）
- `NORMAL` - 相邻页面（预渲染）
- `LOW` - 其他页面（按需渲染）

---

#### 5. 为什么在 `onCleared()` 中保存书签？

**原因**：
- `onCleared()` 是 ViewModel 的**生命周期回调**，在以下情况会触发：
  - 用户按返回键退出阅读器
  - 系统回收内存
  - 配置变更（屏幕旋转）
- 在这些情况下保存书签，可以确保**书签位置不丢失**

**实现**：
```kotlin
override fun onCleared() {
    super.onCleared()
    cleanup()
}

private fun cleanup() {
    // 1. 保存书签
    saveBookmark()

    // 2. 关闭 PdfRenderer
    closePdfRenderer()

    // 3. 回收 Bitmap
    bitmapCache.snapshot().values.forEach { it.recycle() }
    bitmapCache.evictAll()
}
```

---

## 旧架构：单页模式

### 设计目标

1. **简单直观**：一次显示一页 PDF
2. **轻量**：无需复杂的状态管理和手势处理
3. **兼容**：适用于低端设备

---

### 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        UI 层                               │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  ReaderScreen.kt (Compose)                        │   │
│  │  - 显示当前页 Bitmap                              │   │
│  │  - 左右滑动翻页                                   │   │
│  │  - 顶部/底部工具栏                                │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                           ↓ 状态观察
┌─────────────────────────────────────────────────────────────┐
│                      ViewModel 层                            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  ReaderViewModel.kt (AndroidViewModel)             │   │
│  │  （与新模式共用同一个 ViewModel）                  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

### 核心组件

#### **ReaderScreen.kt**（Compose UI）

**职责**：
- 显示当前页 Bitmap（`_currentBitmap`）
- 左右滑动翻页（`pointerInput` + `detectHorizontalDragGestures`）
- 显示顶部/底部工具栏
- 观察 ViewModel 状态

**关键实现**：
```kotlin
@Composable
fun ReaderScreen(
    fileId: String,
    onBack: () -> Unit,
    navController: NavController,
    viewModel: ReaderViewModel = viewModel()
) {
    // 1. 观察当前 Bitmap
    val currentBitmap by viewModel.currentBitmap.collectAsStateWithLifecycle()

    // 2. 显示 Bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = "PDF 页面"
        )
    }

    // 3. 左右滑动翻页
    Box(
        modifier = Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { change, dragAmount ->
                if (dragAmount > 0) {
                    // 向右滑动 → 上一页
                    viewModel.previousPage()
                } else {
                    // 向左滑动 → 下一页
                    viewModel.nextPage()
                }
            }
        }
    )

    // 4. 顶部工具栏
    TopAppBar(...)

    // 5. 底部工具栏
    ReaderBottomBar(...)
}
```

**限制**：
- ❌ 不支持连续滚动（只能单页显示）
- ❌ 不支持缩放
- ❌ 不支持惯性滚动

---

## 数据流与交互流程

### 完整数据流

```
用户操作（触摸/点击）
    ↓
ContinuousCanvasGestureHandler / ReaderScreen
    ↓
ReaderViewModel（更新状态）
    ├─ _currentPage.value = index
    ├─ _currentBitmap.value = bitmap
    └─ preferencesManager.setXxx()
            ↓
StateFlow 通知 UI 层
    ↓
ReaderScreenV2 / ReaderScreen
    ├─ 重组 Compose UI
    └─ 更新 PdfContinuousView 状态
            ↓
PdfContinuousView.invalidate() → onDraw()
    ↓
绘制页面（从 PageCacheManager 获取 Bitmap）
```

---

### 设置数据流

```
用户点击设置项（例如切换翻页方式）
    ↓
ReaderViewModel.setPageMode("scroll")
    ↓
preferencesManager.setReaderPageMode("scroll")
    ↓
PreferencesManager Flow 通知所有观察者
    ↓
ReaderViewModel.pageMode Flow 更新
    ↓
ReaderScreenV2 重组
    ↓
根据 pageMode 切换显示模式
```

---

## 技术决策与优化

### 性能优化

#### 1. Bitmap 复用

**问题**：频繁创建/回收 Bitmap 会导致 **GC（垃圾回收）** 频繁触发，造成卡顿。

**解决方案**：使用 `LruCache` 复用 Bitmap。

```kotlin
// 渲染页面时，先从缓存获取
val cachedBitmap = bitmapCache.get(index)
if (cachedBitmap != null) {
    _currentPage.value = index
    _currentBitmap.value = cachedBitmap
    return@launch
}

// 缓存未命中，渲染新 Bitmap
val bitmap = renderPageInternal(index)
bitmapCache.put(index, bitmap)
```

---

#### 2. 预渲染

**问题**：用户滚动到某页时，如果该页未渲染，会出现**白屏**。

**解决方案**：预渲染相邻页面。

```kotlin
fun goToPage(index: Int) {
    // 1. 立即显示当前页（如果缓存命中）
    val cachedBitmap = bitmapCache.get(index)
    if (cachedBitmap != null) { ... }

    // 2. 渲染当前页（如果缓存未命中）
    viewModelScope.launch {
        if (cachedBitmap == null) {
            renderPageInternal(index)?.let { bmp ->
                bitmapCache.put(index, bmp)
            }
        }

        // 3. 预渲染下一页
        if (index + 1 < _totalPages.value && bitmapCache.get(index + 1) == null) {
            renderPageInternal(index + 1)?.let { bmp ->
                bitmapCache.put(index + 1, bmp)
            }
        }

        // 4. 预渲染上一页
        if (index - 1 >= 0 && bitmapCache.get(index - 1) == null) {
            renderPageInternal(index - 1)?.let { bmp ->
                bitmapCache.put(index - 1, bmp)
            }
        }
    }
}
```

---

#### 3. 并发限制

**问题**：无限制并发渲染会导致 **OOM**。

**解决方案**：使用 `Semaphore(2)` 限制并发数。

```kotlin
private val renderSemaphore = Semaphore(2)

suspend fun getPageBitmap(index: Int): Bitmap? = withContext(Dispatchers.IO) {
    // 1. 获取信号量（限制并发）
    renderSemaphore.acquire()
    try {
        // 2. 渲染页面
        val bitmap = renderPageInternal(index)
        return@withContext bitmap
    } finally {
        // 3. 释放信号量
        renderSemaphore.release()
    }
}
```

---

#### 4. 取消不必要的渲染任务

**问题**：用户快速滚动时，会产生**大量渲染请求**，其中很多已经不在可见区域。

**解决方案**：在 `updateVisiblePages()` 中取消不可见页面的渲染任务。

```kotlin
private fun renderVisiblePages() {
    val iterator = renderJobs.keys.iterator()
    while (iterator.hasNext()) {
        val pageIndex = iterator.next()
        if (pageIndex !in visiblePages) {
            // 取消不可见页面的渲染任务
            renderer.cancelRender(pageIndex)
            iterator.remove()
        }
    }

    // 提交可见页面的渲染任务
    for (pageIndex in visiblePages) {
        if (!renderJobs.containsKey(pageIndex)) {
            val job = renderer.renderPageAsync(...) { bitmap ->
                cache.put(pageIndex, bitmap)
                invalidate()
            }
            renderJobs[pageIndex] = job
        }
    }
}
```

---

### 内存优化

#### 1. Bitmap 回收

**问题**：Bitmap 占用大量内存，如果不及时回收，会导致 **OOM**。

**解决方案**：在 `LruCache.entryRemoved()` 中自动回收 Bitmap。

```kotlin
cache = object : LruCache<Int, Bitmap>(maxCacheSize) {
    override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
        // 当 Bitmap 被移除时，回收它
        if (evicted && !oldValue.isRecycled) {
            oldValue.recycle()
        }
    }
}
```

---

#### 2. 按需渲染

**问题**：如果一次性渲染所有页面，会**占用大量内存**。

**解决方案**：只渲染**可见页面** + **相邻页面**。

```kotlin
private fun updateVisiblePages() {
    val visible = mutableSetOf<Int>()
    val viewportTop = scrollOffset
    val viewportBottom = scrollOffset + viewportHeight

    for (i in 0 until totalPages) {
        val pageTop = pageYOffsets[i]!!
        val pageBottom = pageTop + pageHeights[i]!!

        // 只添加可见页面
        if (pageBottom > viewportTop && pageTop < viewportBottom) {
            visible.add(i)
        }
    }

    visiblePages = visible
}
```

---

## 文件清单

### 新架构（连续画布模式）

| 文件路径 | 职责 | 行数 |
|---------|------|------|
| `ui/screen/reader/ReaderScreenV2.kt` | Compose UI（连续画布模式） | 145 |
| `ui/screen/reader/PdfContinuousView.kt` | 自定义 View（连续画布核心） | 297 |
| `ui/screen/reader/ContinuousCanvasState.kt` | 状态管理（页面坐标、可见页面） | 213 |
| `ui/screen/reader/ContinuousCanvasGestureHandler.kt` | 手势处理（滚动/缩放/惯性） | 303 |
| `ui/screen/reader/PageCacheManager.kt` | 缓存管理（LRU 缓存） | 155 |
| `ui/screen/reader/PdfRendererManager.kt` | 渲染管理（优先级队列） | 216 |
| `ui/screen/reader/ReaderViewModel.kt` | ViewModel（与新/旧模式共用） | 470 |

---

### 旧架构（单页模式）

| 文件路径 | 职责 | 行数 |
|---------|------|------|
| `ui/screen/reader/ReaderScreen.kt` | Compose UI（单页模式） | 16604（可能包含大量注释/测试代码） |
| `ui/screen/reader/ReaderViewModel.kt` | ViewModel（与新/旧模式共用） | 470 |

---

### 共享组件

| 文件路径 | 职责 |
|---------|------|
| `ui/screen/reader/ReaderSettingsScreen.kt` | 阅读设置页面 |
| `data/local/PreferencesManager.kt` | 设置持久化 |
| `data/model/PdfFile.kt` | PDF 文件数据模型 |
| `data/repository/PdfRepository.kt` | 数据库访问 |

---

## 下一步建议

### 1. 完善 `ReaderScreenV2.kt`

**当前问题**：
- `AndroidView` 的 `update` 块中，初始化逻辑不完整
- 没有处理 `pdfRenderer` 为 `null` 的情况
- 没有处理配置变更（屏幕旋转）

**建议**：
```kotlin
AndroidView(
    factory = { ctx ->
        PdfContinuousView(ctx).apply {
            // 可以在这里设置默认参数
        }
    },
    update = { view ->
        val renderer = viewModel.pdfRenderer
        if (renderer != null && !view.isInitialized) {
            view.init(renderer)
        }
    }
)
```

---

### 2. 优化 `ContinuousCanvasState`

**当前问题**：
- `calculatePageLayout()` 会**打开所有页面**获取高度，如果 PDF 页数很多（例如 1000 页），会**非常慢**。

**建议**：
- 使用**异步计算**（在 IO 线程中计算）
- 使用**缓存**（将页面高度持久化到数据库）

---

### 3. 添加单元测试

**当前状态**：没有单元测试。

**建议**：
- 为 `ContinuousCanvasState` 编写单元测试（测试页面坐标计算、可见页面计算）
- 为 `PageCacheManager` 编写单元测试（测试缓存命中/淘汰）
- 为 `PdfRendererManager` 编写单元测试（测试优先级队列、并发限制）

---

### 4. 性能测试

**建议**：
- 测试**大文件**（1000+ 页）的滚动性能
- 测试**内存占用**（在不同缓存大小下）
- 测试**渲染速度**（在不同并发数下）

---

## 总结

### 新架构优点

✅ **用户体验优秀**：连续滚动、手势缩放、惯性滚动  
✅ **性能优秀**：直接 Canvas 绘制、LRU 缓存、并发限制  
✅ **内存可控**：LRU 缓存自动淘汰、Bitmap 自动回收  

### 新架构缺点

❌ **代码复杂度高**：需要维护 6 个核心类  
❌ **初始化慢**：`calculatePageLayout()` 需要打开所有页面  
❌ **兼容性未知**：需要在低端设备上测试  

### 旧架构优点

✅ **代码简单**：只有 1 个 UI 文件 + 1 个 ViewModel  
✅ **兼容性优秀**：适用于所有设备  
✅ **初始化快**：无需计算页面布局  

### 旧架构缺点

❌ **用户体验差**：只能单页显示，无法连续滚动  
❌ **功能受限**：不支持缩放、惯性滚动  

---

**建议**：
- 如果追求**用户体验**，使用新架构（连续画布模式）
- 如果需要**兼容性**，保留旧架构（单页模式）作为备用
- 长期目标：**完善新架构，逐步废弃旧架构**

---

**文档版本**: v1.0  
**最后更新**: 2026-06-15  
**作者**: 齐活林（Qi）· 交付总监
