# WHPU Pocket

本项目基于 [ZZUPocket](https://github.com/3beuml/ZZUPocket) 开发，面向武汉轻工大学。支持学校网页交互登录、真实课表导入、成绩和考试查询，以及校园卡余额、完整账单和三个电费分组的原生查询与离线缓存。首次启动可查看明确标注的虚构演示课表，第一周日期和作息由用户按学期设置。保留今日安排、桌面小组件、番茄钟、学习统计与上课免打扰；图书借阅和座位预约使用官方网页入口。

测试版下载见 [GitHub Releases](https://github.com/sky-initial/WHPU-campus/releases)，安装与签名说明见 [发布说明](docs/whpu-release.md)。

构建要求和迁移进度见 [P1 开发记录](docs/whpu-p1.md)。原项目许可证和版权声明保留在 LICENSE 中。

后续功能顺序与验收标准见 [后续任务计划](docs/whpu-roadmap.md)。

## 项目来源

基于郑州大学校园助手 ZZUPocket（Android）渐进适配，保留原项目 MIT 许可证与版权声明。

[![License](https://img.shields.io/github/license/3beuml/ZZUPocket.svg?style=flat)](LICENSE)
[![Release](https://img.shields.io/github/v/release/sky-initial/WHPU-campus?include_prereleases&style=flat&label=release)](../../releases)
[![Android](https://img.shields.io/badge/Android-12%2B-3ddc84?style=flat&logo=android&logoColor=white)](#构建)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7f52ff?style=flat&logo=kotlin&logoColor=white)](#技术栈)
[![Tests](https://img.shields.io/badge/rc12%20unit%20tests-589-brightgreen?style=flat)](docs/whpu-rc12-validation.md)

> ⚠️ 本项目是非官方项目。
> 接口来自公开网页的协议整理与社区项目经验，**随时可能失效或被学校调整**；



## 功能特性

- **学校课表**：学校网页交互登录、真实课程与实践安排导入、按学期设置第一周日期和第 1—11 节作息、周次切换、本周高亮、自定义日程及显示设置。
- **今日安排**：读取本地课程、日程和当日考试；显示考试时间、考场及状态。
- **成绩与考试**：点选学年和学期、完整分页同步、离线缓存；逐门展示学校返回的学分、绩点和学分绩点，不自行计算总 GPA 或排名。
- **专注与统计**：保留番茄钟、专注标签、学习统计与热力图。
- **上课免打扰**：用户授权并开启后，按当前真实课表调度；允许闹钟并沿用系统优先通知设置。
- **校园卡**：学校网页登录后手动同步余额与完整账单，按账号和卡分区缓存；今日页及余额小组件读取所选卡。
- **宿舍电费**：三个官方分组均可原生选择房间、查询人民币余额；保留完整小数精度，查询失败时保留旧缓存及更新时间。
- **桌面小组件**：今明课表与下一项安排混排课程、考试，校园卡与电费显示所选缓存；点击进入对应页面，小组件只读取本地缓存。
- **外观**：支持主题、深浅色、课表背景、格子内容和密度设置。登录控制区与实践安排可折叠。

校园卡和电费保留官方网页登录入口，缴费由用户在官方页面完成。图书借阅尚缺可用认证条件，保留外部浏览器入口；座位预约已确认 Android 登录进入列表，实际预约、取消和签到由用户操作。空教室已移出开发范围。当前功能与验收范围见 [发布说明](docs/whpu-release.md)。

## 下载安装

到 [Releases](../../releases) 选择最新候选版本，下载对应的 `WHPU-Pocket-<版本>.apk` 安装
（Android 12 / API 31 及以上）。

- 装在手机上会走系统的"未知来源应用"授权；Flyme / MIUI 这类系统还需要在
  「设置 → 应用 → 权限」里给文件管理器或浏览器放行"安装未知应用"。
- rc1 至 rc12 沿用同一发布签名；rc12 已验证正式 rc11 覆盖升级保留人工业务数据和设置。Debug 开发版签名不同，不能直接覆盖；卸载会删除本地数据，详见 [安装及更新](docs/whpu-release.md#安装及更新)。


## 首次使用

1. **查看演示**：首次启动无需登录，课表中的演示数据有明确标识。
2. **导入真实课表**：从“更多”或课表页进入“学校登录与课表同步”，在学校网页完成登录，选择学年和学期，点击“同步到课表”；此时无需填写开学日期。
3. **设置教学周**：课表保存成功后，使用年／月／日滚轮选择第一周周一，确认后进入课表。日期按学期保存，后续可在课表设置中用同样的滚轮修改；应用不会把某位用户提供的开学日期设为所有人的默认值。
4. **离线查看**：成功导入的课程、实践安排和自定义日程保存在本机，课表及今日页可离线读取。清除网页登录状态会保留离线课表；同步失败或保存前取消同步也会保留原缓存。
5. **账号归属与切换**：旧版学校课表没有账号标记，首次同步需要确认归属。若属于当前账号，选择“属于当前账号，保留并绑定”，保留已有课表和显示修改；仅有演示课表时无需此确认。切换到其他账号后，确认并成功获取新数据才统一清理所有学期的旧学校课表、成绩和考试缓存及课程显示修改。自定义日程、演示课表和日期设置保留；取消确认不修改缓存。
6. **成绩查询**：从“更多 → 成绩查询”选择学年和学期，登录学校后刷新。全部分页读取成功后保存本地快照，可离线查看；展示学校返回的成绩、学分、绩点、学分绩点和成绩性质，补考记录单独保留，不自行计算总 GPA 或排名。成绩和课表共享账号归属，换账号确认会统一处理两类缓存。接口证据及验证进度见 [成绩查询开发记录](docs/whpu-grades.md)。
7. **校园卡与电费**：从“更多”进入对应原生页面，打开学校登录，经融合门户的“虚拟校园卡”完成会话交接。校园卡手动同步余额和账单；电费选择分组及房间后查询。缓存可离线读取，应用进程结束后的联网刷新需重新交接学校会话。缴费在官方页面完成。
8. **图书馆**：借阅通过外部浏览器打开 OPAC，使用独立登录；座位预约通过应用内超星页面完成。尚未实现原生借阅缓存，不自动预约、取消、签到或续借。

## 项目结构

```
app/src/main/java/com/zzupocket/app/
├── core/            纯逻辑与基础设施：手动依赖容器 AppGraph、加密、HTTP 客户端、
│                    错误文案、课表文本、登录输入体检、学期过滤
├── data/
│   ├── whpu/        WHPU 教务、成绩、考试、校园卡与电费的会话、请求、解析及缓存
│   ├── auth/        保留的原郑大认证代码
│   ├── eas/         保留的原郑大教务协议
│   ├── ecard/       保留的原郑大一卡通协议
│   ├── lib/         保留的原图书馆协议与探测
│   ├── db/          Room 实体与 DAO
│   ├── prefs/       DataStore 偏好及保留的旧认证存储
│   ├── room/        保留的原郑大宿舍楼房间表
│   ├── repo/        Repository：把接口与本地库缝在一起
│   └── work/        保留的旧电量采样代码，WHPU 不启用
├── domain/          纯数据模型与统计口径（课表、用电、专注、校园卡、周次对齐）
├── focus/           番茄钟引擎、通知、前台服务
└── ui/              Compose 界面：today / schedule / focus / campus / library /
                     widget（桌面小组件）/ login / about / diag / theme / nav

app/src/main/res/    资源：布局（含 widget_* 小组件布局）、配色、字符串、预览图
docs/                项目文档（接口契约、实测坑、设计推演），见下方「文档」
tools/               开发脚本（如按真机几何生成小组件预览图）
```

## 技术栈

| 方面 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin 2.0 + Jetpack Compose + Material 3（含 Material You 动态取色与 8 套预设配色） |
| 依赖注入 | 单模块、手动依赖容器 `core/AppGraph.kt` |
| 网络 | OkHttp + kotlinx.serialization，学校网页交互登录后交接原生查询会话 |
| 本地存储 | Room（课表、成绩、考试、专注及校园卡/电费快照）、DataStore（偏好） |
| 后台 | 前台服务（番茄钟）+ AlarmManager（小组件刷新与上课免打扰）；旧电费后台采样关闭 |
| 桌面小组件 | AppWidgetProvider + RemoteViews + XML（**不用 Glance**，理由见 `docs/design/UI与登录设计.md` §2.6） |
| 后端 | **没有**。不经过任何自建中转服务器，账号凭据与数据只在设备和学校系统之间流动 |


## 构建

使用 JDK 17、Android SDK 36；工程保持 Gradle 8.9、AGP 8.7.3 和 Kotlin 2.0.21。将 `local.properties` 的 `sdk.dir` 指向本机 Android SDK，然后在工程目录执行：

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。正式构建需要维护者在仓库外保存的签名密钥，配置方式见[签名维护](docs/whpu-release.md#签名维护)；设备测试与构建环境记录见 [P1 开发记录](docs/whpu-p1.md)。

## 隐私与边界

- **官方网页登录**：账号密码由用户在学校官方页面填写。原生请求使用的教务 Cookie、校园卡令牌仅驻留进程内存；学校网页自身可由 WebView 保留会话。清除应用内网页登录状态会清除所有内置校园网页会话，并保留离线业务缓存；外部浏览器不受影响。
- **本地缓存**：课表、成绩、考试、专注记录、校园卡和电费快照保存在本机，应用直连学校服务，没有自建数据中转服务器。卸载 App 会删除本地数据。
- **业务操作**：原生接口只查询；预约、取消、签到、续借及支付由用户在学校官方页面操作，不做自动抢座或自动签到。
- **不采集**：没有统计 SDK、没有崩溃上报、没有广告。
- 仓库本身**不接收**任何把账号密码、令牌、个人房间号写进代码或文档的提交。


## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/`](docs/README.md) | 文档索引 |
| [校园卡接口核验](docs/whpu-campus-card-evidence.md) | WHPU 余额、完整流水、认证交接及实际验证范围 |
| [电费开发记录](docs/whpu-electricity-native.md) | 三个分组的原生查询、人民币依据、缓存兼容与显示验证 |
| [校园服务网页入口](docs/whpu-campus-services.md) | 官方入口、WebView 生命周期和借阅/预约边界 |
| [后续路线图](docs/whpu-roadmap.md) | 已交付范围和仍依赖实际条件的验收 |
| [`docs/interfaces/`](docs/interfaces/) / [`docs/design/`](docs/design/) | 原 ZZUPocket 接口调查和设计资料，作为历史参考；不代表 WHPU 接口依据 |




## 致谢

原 ZZUPocket 的接口协议（CAS 登录、教务 EAS、一卡通）参考 / 对齐了以下社区项目，均为 MIT 许可；WHPU 适配依据另见 `docs/whpu-*` 记录：

- [Illustar0/ZZU.Py](https://github.com/Illustar0/ZZU.Py) —— 豫见郑大的 Python API 封装（MIT）
- [Elykia093/ZZU-Electricity-Monitor](https://github.com/Elykia093/ZZU-Electricity-Monitor) ——
  宿舍电量监控（MIT）：**内置房间表（`assets/rooms.json`）由它的 `rooms/99.json` 精简而来**，
  定时采样节奏与低电量阈值也参考了它
- [rimrose/ZZU-Dorm-Electricity-Balance-Base](https://github.com/rimrose/ZZU-Dorm-Electricity-Balance-Base) ——
  宿舍用电记录（MIT）

桌面小组件的版式参考了 [拾光课程表](https://github.com/ShiGuangSchedule/shiguangschedule) 的实现思路
（Apache-2.0；只参考了设计，没有拷贝代码）。

## 许可证

[MIT](LICENSE) © 2026 3beuml
