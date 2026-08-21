# 贡献指南

感谢你对 Winfex 的兴趣！本文件说明如何参与开发。

## 开发环境

- **Android Studio**: Hedgehog (2023.1.1) 或更高
- **JDK**: 17
- **Android SDK**: API 34（compileSdk），API 28（minSdk）
- **NDK**: r26b (`26.1.10909125`)
- **CMake**: 3.22.1
- **Git**: 2.20+
- 约 2 GB 磁盘空间（xserver submodule）

## 快速开始

```bash
# 1. Fork & clone
git clone --recurse-submodules https://github.com/<your-username>/winfex.git
cd winfex

# 2. 添加上游
git remote add upstream https://github.com/winfex/winfex.git

# 3. 同步 X Server 源码（必须）
./scripts/sync-xserver.sh

# 4. 配置 SDK 路径
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 5. 编译
./gradlew assembleDebug

# 6. 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 代码规范

### Kotlin
- 4 空格缩进，不用 tab
- 行宽上限 120 字符
- 遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- 公共 API 必须有 KDoc 注释
- data class 优先于普通 class

### C / C++
- 4 空格缩进
- 函数命名：`winfex_*` 前缀（避免和系统库冲突）
- 文件命名：`snake_case.c/.h`
- JNI 函数严格遵循 `Java_<package>_<Class>_<method>` 命名

### XML
- 资源命名：
  - layout: `fragment_*` / `item_*` / `activity_*` / `dialog_*`
  - id: `tv_*` (TextView) / `btn_*` (Button) / `et_*` (EditText) / `sw_*` (Switch) / `actv_*` (AutoCompleteTextView)
  - drawable: `ic_*` (icon) / `bg_*` (background)
  - color: 全小写下划线
  - string: `<feature>_<action>` 例如 `prefix_create`

## 提交规范

### Commit message
遵循 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <subject>

<body>

<footer>
```

类型：
- `feat`: 新功能
- `fix`: Bug 修复
- `refactor`: 重构
- `docs`: 文档
- `ci`: CI/CD
- `chore`: 杂项
- `test`: 测试
- `perf`: 性能优化

示例：
```
feat(input): add XTest injector for keyboard/mouse events

- New cpp/xtest_injector.c with dlopen-based libX11/libXtst loading
- New XTestInjector.kt wrapper with connect/injectKey/injectMouseButton
- InputController.dispatchKey now calls XTestInjector
- Tested with winecfg: pressing virtual A button types 'a'

Closes #42
```

### PR 流程
1. 从 `main` 切分支：`git checkout -b feat/my-feature`
2. 提交若干 commit（不要 squash，让 reviewer 能看清演进）
3. push 到自己的 fork
4. 发 PR 到 `winfex/winfex:main`
5. CI 必须通过（`pr-check` job）
6. 至少一个 maintainer review 通过后合并

### 分支策略
- `main` — 始终可发布的稳定分支
- `feat/*` — 功能分支
- `fix/*` — Bug 修复分支
- `release/*` — 发布预备分支（如有需要）

## 测试

### 单元测试
```bash
./gradlew test
```

### Instrumented 测试
```bash
./gradlew connectedAndroidTest
```

### 手动测试清单
发版前必须手动验证：
- [ ] APK 能装能起
- [ ] 5 个 tab 都能切换
- [ ] 导入一个 .rat 包成功
- [ ] 创建一个 Wine 前缀成功
- [ ] 启动 X server，socket 文件存在
- [ ] 激活一个输入 profile，虚拟按钮有视觉反馈
- [ ] 按 X server 状态条目，能切换启动/停止

## 发布流程

1. 更新 `CHANGELOG.md`
2. 更新 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`
3. 提交：`git commit -m "chore: bump version to x.y.z"`
4. 打 tag：`git tag -a vx.y.z -m "Release vx.y.z"`
5. push：`git push origin main --tags`
6. GitHub Actions 自动构建并发布 Release

### 版本号规则
- `0.x.y` — alpha 阶段，API 不稳定
- `1.0.0` — 第一个稳定版
- `MAJOR.MINOR.PATCH`：
  - MAJOR：破坏性变更
  - MINOR：新功能（向后兼容）
  - PATCH：Bug 修复
- 预发布：`0.4.0-alpha.1` / `0.4.0-beta.2` / `0.4.0-rc.1`

## 项目结构

详见 [README.md §2 项目结构](README.md#2-项目结构)。

关键目录：
- `app/src/main/java/com/winfex/core/` — 核心业务逻辑
- `app/src/main/java/com/winfex/ui/` — UI 层（Fragment / Activity / Adapter）
- `app/src/main/java/com/winfex/model/` — 数据类
- `app/src/main/java/com/winfex/input/` — 输入系统（XTestInjector）
- `app/src/main/java/com/winfex/native/` — JNI 桥声明
- `app/src/main/cpp/` — C/C++ 源码（libwinfex.so）
- `xserver/` — X Server Gradle module（默认 stub，sync 后变完整）
- `scripts/sync-xserver.sh` — 同步 termux-x11 Lorie DDX 源码

## 联系

- Issue：https://github.com/winfex/winfex/issues
- Discussion：https://github.com/winfex/winfex/discussions
- 邮件：winfex@example.com

## License

贡献的代码遵循 [GPL-3.0](LICENSE) 发布。
