# WHPU Pocket P1 开发记录

## 阶段状态

当前应用首次启动展示演示课表，支持学校交互登录与真实课表导入。用户已在 OPD2407 上确认成功导入；真实课表离线冷启动和今日页缓存来源已验证，用户也确认桌面小组件测试完成并成功点击跳转。导入前无需填写日期，保存后用年／月／日滚轮设置第一周周一，用户已操作通过。最新字段与认证证据见 [P1.6 核验记录](whpu-p1.6-evidence.md)。

2026-11-08 最新验证：400 项本地单元测试和 15 项 Room 设备测试全部通过。Debug 应用和测试 APK 均已构建成功并安装到设备。下文 2026-11-07 的 379/9 项结果为历史基线。

| 阶段 | 已交付内容 | 待验证内容 |
| --- | --- | --- |
| P1.1 | 原项目构建基线、AppGraph 依赖与课表缓存复用方案 | 无 |
| P1.2 | 应用名称、applicationId、W 字标及未接入业务入口隔离，保留 MIT 版权 | 校园品牌资源可后续完善 |
| P1.3 | WHPU Provider、周次与课程解析、内存 CookieJar、Mock 数据及脱敏真实响应回归，真实 POST 导入通过 | 会话过期、验证码或 MFA |
| P1.4 | Compose 周课表、今日页、实验课、实践安排缓存、自定义日程及小组件放置跳转验证 | 更多启动器和主题兼容性 |
| P1.5 | Debug APK、单元测试、真机测试记录和 Room 设备回归用例 | 小组件主题兼容性仍待验证 |

实际工程使用单模块和手动依赖容器 AppGraph。本阶段复用既有 Repository、Room 实体和 Compose 网格，新增 WHPU Provider 与映射逻辑。

## 构建

使用 JDK 17、Android SDK 36 和项目的 Gradle Wrapper。设置 `ANDROID_HOME`，或在本机的 `local.properties` 中设置 `sdk.dir`。运行 `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`，Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 当前实现

- 本地演示课表包含虚构讲课、实验、单双周和校区，首次使用时写入 Room。演示学期起点为 2026-08-31，节次时间均为虚构示例。
- WHPU 教务请求层使用内存 CookieJar，用户在学校 WebView 页面完成登录后，读取课表端点适用的会话并尝试同步。WebView 使用应用自己的浏览器存储；请求层不把 Cookie 写入源码、日志或文档。
- 课表解析保留原始字段，实践课程独立解析。周次解析支持范围、多段和单双周。
- 原校园服务入口标为暂未接入，原校园网络客户端被阻断，电费后台采样取消。
- 保留上游 MIT 许可证及版权声明。应用 ID 为 `com.whpupocket.app`。

## 待验证和后续工作

- 用户已完成当前设备的小组件测试并点击跳转成功，后续可扩展其他启动器和主题兼容性验证。
- 已用脱敏真实响应核对节次、单双周及实践课字段。页面学期选项为第一学期 `3`、第二学期 `12`；本次真实数据查询为 `xnm=2026`、`xqm=3`，第二学期数据尚未请求。
- P1.6 需确认学校授权登录方式、实际 CAS/Rump 与教务会话关系、会话失效和退出流程，以及校历和作息。
- 真实 Provider 已接入导入界面，完整响应解析后事务保存学期、排课与实践安排。不同数据源使用不同的本地学期 ID，真实会话交接已由用户确认导入成功。第一周日期在导入后设置；常规课程教师按当前排课／本周显示，实践安排保留全部教师。
- 实践安排已适配 `qsjsz`、`jsxm` 和 `xqmc`，兼容旧演示缓存；学校实际网络与会话行为仍待验证。

## 2026-11-07 真机测试

测试设备为 OPPO PJJ110，Android 16，使用用户当前自定义主题。

- 安装与冷启动成功，无需登录即可打开演示课表。
- 第 10 周显示双周程序设计课，第 11 周显示单周实验课；实验课详情中的教师、教室、节次与单双周一致。
- 暂时关闭移动数据并冷启动，常规课程与实践安排仍正常显示。测试后已恢复原网络状态。
- 临时自定义日程经过保存、重启、演示课表刷新后仍存在，随后通过界面删除。
- Room 数据库快照包含 32 条 `whpu-demo` 排课及独立实践安排缓存。
- 番茄钟开始、暂停、继续、后台返回正常，前台服务正常运行。测试产生的短时记录已通过界面删除，测试应用已授予通知权限。
- 图书馆入口显示未接入提示。今日页旧登录说明已改为演示模式，并将校园服务统一标为暂未接入。
- 系统已注册三个小组件，但当前桌面卡片选择器搜索 WHPU 无结果。用户说明自定义主题可能影响卡片加载，主题关联尚未验证。
- 修复一处旧测试对系统时区的依赖后，374 项单元测试全部通过。
- 修复“更多”和“关于”页状态栏留白，最终 APK 已重新安装并验证页面位置和演示缓存。

当前执行环境可通过指定较短的 `jdk.net.unixdomain.tmpdir` 运行 Gradle，避免默认临时目录导致的本地连接错误。该设置仅用于本机构建，不写入项目 Gradle 配置。

## 提交前审查

- 常规课程缺失、为空或为 null 的周次现按解析失败处理，刷新保留上一份缓存。实践课程允许尚无确定周次。
- 本轮收尾运行 `:app:testDebugUnitTest :app:assembleDebug` 成功，41 个测试类共 379 项测试，失败 0 项、跳过 0 项。新增回归覆盖周次缺失、null、空白及解析失败不触发缓存替换。
- 单元测试与本地 HTTP 测试使用人工构造数据。local.properties、APK、真机截图、数据库快照及构建产物均由 Git 忽略规则排除。
- 原郑大协议实现保留在源码中，其应用入口已隔离，共享校园客户端与研究生客户端已阻断。尚未实施全量网络抓包验证。

## 修改文件清单

- 工程与文档涉及 README.md、app/build.gradle.kts 和本开发记录。
- 启动与依赖涉及 MainActivity.kt、ZZUPocketApp.kt、core/AppGraph.kt、core/net/HttpClients.kt、data/gms/GmsClient.kt。
- 数据层新增 data/whpu 下的 WeekParser.kt、WhpuSchedule.kt、WhpuAcademicProvider.kt、WhpuAcademicRepository.kt、MockWhpuScheduleProvider.kt，并修改 data/repo/ScheduleRepository.kt。
- 界面涉及 ui/nav/AppNav.kt、ui/schedule/ScheduleScreen.kt、ui/schedule/ScheduleViewModel.kt、ui/today/TodayScreen.kt、ui/today/TodayTimelineCard.kt、ui/widget/WidgetData.kt。
- 资源涉及 drawable/ic_launcher_foreground.xml、values/strings.xml、xml/network_security_config.xml。
- 测试新增 data/whpu 下六个测试文件，并修正 ui/widget/WidgetBalanceTest.kt 的系统时区假设。

以上代码路径相对于 app/src/main/java/com/zzupocket/app，资源路径相对于 app/src/main/res，测试路径相对于 app/src/test/java/com/zzupocket/app。

## P1.6 接入所需证据

1. 脱敏课表 JSON，覆盖 kbList、sjkList、教学类型字典和星期映射。移除 xsxx 学生信息及其他个人字段；保留字段类型、空值、周次、jcs/jcor 和 ID 之间的关联。
2. 学校允许使用的登录入口与真实跳转顺序。设备上已观察到 CAS 登录页面和 sec 域名跳转，完整会话建立仍待核验；独立 HTTP 客户端遇到 Rump/WAF 403。由用户在学校页面完成交互认证，只记录不含个人信息的域名和状态码，不记录密码、票据或 Cookie 值。
3. 教务会话的建立、过期、403 和退出行为，以及验证码或 MFA 出现时的交互要求。
4. 学校校历、上课作息及其他学期数据。已核对页面中的第一、第二学期代码，尚未查询第二学期实际数据。

已取得第一学期真实响应并制作最小脱敏样本，解析与映射回归通过。Android 学校交互登录、真实导入、离线课表及今日页已验证，用户确认桌面小组件显示与点击跳转成功。后续认证边界与最新回归见 [P1.6 证据记录](whpu-p1.6-evidence.md)。

## 2026-11-08 登录与同步稳定性

- 基于已合并的 PR #3 继续开发，增加缓存账号归属、换账号确认、旧版缓存首次确认、可取消同步及会话版本校验。
- 同账号保留其他学期；用户确认换账号后，完整获取新数据，再原子替换所有旧学校课表及其显示修改。自定义日程、演示数据和学期日期设置保留。失败或取消不先清空缓存。
- 清除网页登录态保留课表，清除范围包括应用 WebView Cookie、WebStorage 和内存会话；学校服务端全局退出仍未验证。
- 413 项单元测试与 22 项 Room 真机测试通过，应用与测试 APK 构建成功，新版安装于 OPD2407。所有新增账号切换测试均使用人工数据和独立临时数据库。
- 真机现有登录成功读取响应并进入旧版归属确认；选择保留后未替换真实课表。真实双账号切换、自然会话过期和验证码/MFA 仍需后续实测。

## 缓存设备回归测试

通过 `gradlew.bat :app:connectedDebugAndroidTest` 在已连接设备上运行 `WhpuCacheTest`。

项目已设置 `android.injected.androidTest.leaveApksInstalledAfterRun=true`，保留设备测试结束后的应用安装和数据。本轮首次使用 Gradle 标准设备测试时，其默认清理行为卸载了应用；用户确认没有个人数据或设置，应用已回装，演示数据已重建。

启用该配置后，标准 Gradle 命令再次通过全部 9 项测试。测试前写入的临时标记和应用数据库在测试后均保留，标记已移除；所有临时测试数据库均已清理。

测试为每个用例建立独立命名的临时 Room 数据库，结束后关闭并删除该测试数据库。用例通过真实 SQLite 验证数据库重开、请求失败保留旧缓存、事务回滚、有效空课表、自定义日程保护、数据源隔离和并发快照一致性。

测试依赖只加入 androidTest 配置，使用 AndroidJUnitRunner，原应用依赖版本保持现有设置。运行器配置参考 [Android 官方设备测试说明](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)。

首轮设备测试复现了清理缓存后实践安排残留的问题。修复后清理同时移除指定来源、指定学期的课程、实践安排和同步记录，保留其他学期及自定义日程。错误来源的清理请求会在写入前拒绝。

修复后 9 项 Room 真机测试全部通过，原有 379 项本地单元测试通过，应用及测试 APK 构建成功。设备测试覆盖数据库关闭重开、请求失败、事务末尾写入失败回滚、成功刷新、有效空课表、错误来源写入与清理、跨学期清理隔离和并发快照一致性。

手机若弹出测试 APK 的安装确认，需要通过系统软件包安装程序完成安装。也可先运行 `:app:assembleDebug :app:assembleDebugAndroidTest`，再用 ADB 安装两个 APK，并执行以下测试命令。

```text
adb shell am instrument -w -r -e class com.zzupocket.app.data.whpu.WhpuCacheTest com.whpupocket.app.test/androidx.test.runner.AndroidJUnitRunner
```
