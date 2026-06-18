# My PDF Manager

一个基于 Jetpack Compose 的 Android PDF 文件管理应用。

## 功能

- 📄 **PDF 阅读** — 支持连续滚动和单页两种阅读模式，手势缩放、页面导航
- 🔖 **收藏管理** — 创建收藏夹，对 PDF 文件分类管理
- 🏷️ **标签系统** — 自定义标签和分类，多维筛选过滤
- 🔍 **全文搜索** — 文件名搜索 + 内容索引搜索
- 🔄 **格式转换** — PDF 转图片，支持页面选择
- 🖼️ **缩略图预览** — 自动生成 PDF 缩略图，快速浏览
- 📤 **导出与分享** — PDF 文件导出到其他应用或目录
- 📦 **ZIP 处理** — 压缩包内 PDF 文件的解压与管理
- ⚙️ **数据库管理** — 查看和管理本地缓存数据

## 技术栈

| 技术 | 版本 |
|------|------|
| Minimum SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |
| Kotlin | 2.0.21 |
| Jetpack Compose | BOM 2024.09.00 |
| Material 3 | ✓ |
| Room Database | 2.7.0 |
| Navigation Compose | 2.8.4 |
| Coil (图片加载) | 2.7.0 |
| DataStore Preferences | 1.1.0 |
| KSP | 2.0.21-1.0.28 |
| Gradle | 8.7 |

## 项目结构

```
app/
├── src/main/java/com/example/pdfmanager/
│   ├── MainActivity.kt          # 主入口
│   ├── PdfManagerApp.kt         # Application 类
│   ├── data/
│   │   ├── local/               # Room 数据库、DAO、搜索引擎
│   │   ├── model/               # 数据模型
│   │   └── repository/          # 仓库层（MVVM Repository）
│   ├── service/                 # 后台服务
│   ├── ui/
│   │   ├── component/           # 可复用 UI 组件
│   │   ├── navigation/          # 导航图
│   │   ├── screen/              # 各页面 Screen + ViewModel
│   │   ├── theme/               # 主题与配色
│   │   └── viewmodel/           # 共享 ViewModel
│   └── util/                    # 工具类
└── build.gradle.kts             # 模块构建配置
```

## 构建方式

使用 Android Studio（最新稳定版）打开项目根目录，或在命令行执行：

```bash
./gradlew assembleDebug
```

## 许可证

本项目仅供学习参考。
