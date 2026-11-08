# WHPU Pocket

本项目基于 [ZZUPocket](https://github.com/3beuml/ZZUPocket) 开发，面向武汉轻工大学。首次启动展示明确标注的虚构演示课表。支持学校页面交互登录、真实课表导入、成绩查询和考试安排，第一周日期由用户按学期设置；已验证真实课表及成绩保存、课表离线冷启动和今日页缓存读取。登录控制区和实践安排支持折叠；可在“更多”中授权并开启上课免打扰。校园卡、电费和图书馆等服务暂未接入。

测试版下载见 [GitHub Releases](https://github.com/sky-initial/WHPU-campus/releases)，安装与签名说明见 [发布说明](docs/whpu-release.md)。

构建要求和迁移进度见 [P1 开发记录](docs/whpu-p1.md)。原项目许可证和版权声明保留在 LICENSE 中。

后续功能顺序与验收标准见 [后续任务计划](docs/whpu-roadmap.md)。

## 原项目说明

郑州大学校园助手（Android）

[![License](https://img.shields.io/github/license/3beuml/ZZUPocket.svg?style=flat)](LICENSE)
[![Release](https://img.shields.io/github/v/release/3beuml/ZZUPocket?style=flat&label=release)](../../releases)
[![Android](https://img.shields.io/badge/Android-12%2B-3ddc84?style=flat&logo=android&logoColor=white)](#构建)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7f52ff?style=flat&logo=kotlin&logoColor=white)](#技术栈)
[![Tests](https://img.shields.io/badge/unit%20tests-223-brightgreen?style=flat)](#参与贡献)

> ⚠️ 本项目是非官方项目。
> 接口来自公开网页的协议整理与社区项目经验，**随时可能失效或被学校调整**；



## 功能特性

**学习**

- **课表**：学期 / 教学周切换，周次表头带日期（`09-30`，今天高亮）、自定义日程（可与教务课混排、撞课提醒）、
  课程总览、自定义课表背景图（含模糊与浓淡调节）、格子内容与密度可调
- **今天**：一条时间轴看"我这一天长什么样"——上课、已完成的专注；外加宿舍电量、校园卡余额与今日消费
- **专注（番茄钟）**：计时引擎 + 前台服务 + 常驻通知，标签管理，周 / 月 / 年统计与热力图
- **教室占用**：按楼筛选，每间教室哪几节有课（数据来自教务系统）

**生活**

- **电费**：照明 / 空调两套表计的余额、每日用量、电费估算、按日明细、余额趋势；
  房间号内置 59 栋楼的房间表（选楼栋 → 选房号），也支持手动填 ID
- **校园卡**：余额、日 / 周 / 月 / 年消费、流水明细与筛选
- **图书馆座位**：内嵌学校原版网页（自动用统一身份认证登录）

**桌面小组件**（RemoteViews + XML，不联网、只读本地数据）

- **下一节课**（2×2）：一节课大字 + 完整时间段 + 地点，右上角圆气泡显示"这节课以及之后还有几节"
- **今明课表**（4×2）：左列今天、右列明天，各列出还没上的课
- **电量与校园卡**（4×2）：左半照明 / 空调余额与"可用约 N 天"，右半校园卡余额与本周消费；
  两块分区可点，分别进电费页 / 校园卡页


## 下载安装

到 [Releases](../../releases) 下载最新的 `ZZUPocket-x.y.z-Pn-release.apk`，在手机上安装即可
（Android 12 / API 31 及以上）。

- 装在手机上会走系统的"未知来源应用"授权；Flyme / MIUI 这类系统还需要在
  「设置 → 应用 → 权限」里给文件管理器或浏览器放行"安装未知应用"。
- App 之间用同一套签名，所以**后续版本可以直接覆盖安装、数据不丢**。


## 首次使用

1. **查看演示**：首次启动无需登录，课表中的演示数据有明确标识。
2. **导入真实课表**：从“更多”或课表页进入“学校登录与课表同步”，在学校网页完成登录，选择学年和学期，点击“同步到课表”；此时无需填写开学日期。
3. **设置教学周**：课表保存成功后，使用年／月／日滚轮选择第一周周一，确认后进入课表。日期按学期保存，后续可在课表设置中用同样的滚轮修改；应用不会把某位用户提供的开学日期设为所有人的默认值。
4. **离线查看**：成功导入的课程、实践安排和自定义日程保存在本机，课表及今日页可离线读取。清除网页登录状态会保留离线课表；同步失败或保存前取消同步也会保留原缓存。
5. **账号归属与切换**：旧版学校课表没有账号标记，首次同步需要确认归属。若属于当前账号，选择“属于当前账号，保留并绑定”，保留已有课表和显示修改；仅有演示课表时无需此确认。切换到其他账号后，确认并成功获取新数据才统一清理所有学期的旧学校课表、成绩缓存及课程显示修改。自定义日程、演示课表和日期设置保留；取消确认不修改缓存。
6. **成绩查询**：从“更多 → 成绩查询”选择学年和学期，登录学校后刷新。全部分页读取成功后保存本地快照，可离线查看；展示学校返回的成绩、学分、绩点、学分绩点和成绩性质，补考记录单独保留，不自行计算总 GPA 或排名。成绩和课表共享账号归属，换账号确认会统一处理两类缓存。接口证据及验证进度见 [成绩查询开发记录](docs/whpu-grades.md)。
7. **其他校园服务**：校园卡、电费、空教室和图书馆等暂未接入；原郑大客户端代码保留在源码中，不代表这些业务已适配轻工大。

## 项目结构

```
app/src/main/java/com/zzupocket/app/
├── core/            纯逻辑与基础设施：手动依赖容器 AppGraph、加密、HTTP 客户端、
│                    错误文案、课表文本、登录输入体检、学期过滤
├── data/
│   ├── auth/        CAS 统一认证（MFA / 短信 / 令牌刷新）
│   ├── eas/         教务系统（课表、节次、教室占用）
│   ├── ecard/       一卡通（电费、校园卡流水）
│   ├── lib/         图书馆（WebView 会话与探测）
│   ├── db/          Room 实体与 DAO
│   ├── prefs/       DataStore（界面偏好、专注偏好、登录冷却）+ 加密令牌
│   ├── room/        宿舍楼房间表（assets/rooms.json）
│   ├── repo/        Repository：把接口与本地库缝在一起
│   └── work/        定时采样电量（WorkManager）
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
| 依赖注入 | **手写**（`core/AppGraph.kt`）—— 依赖不多，P1 最大的风险是接口协议而不是 DI |
| 网络 | OkHttp + kotlinx.serialization（**不用 Retrofit**：接口少，且 CAS 登录的 POST 参数走 query string，裸 OkHttp 更直接） |
| 本地存储 | Room（课表 / 专注 / 电量采样 / 流水）、DataStore（偏好）、EncryptedSharedPreferences + Tink（令牌） |
| 后台 | WorkManager（每 4 小时采样一次电量）+ 前台服务（番茄钟）+ AlarmManager（小组件在上下课时刻刷新） |
| 桌面小组件 | AppWidgetProvider + RemoteViews + XML（**不用 Glance**，理由见 `docs/design/UI与登录设计.md` §2.6） |
| 后端 | **没有**。不经过任何自建中转服务器，账号凭据与数据只在设备和学校系统之间流动 |


## 隐私与边界

- **不保存密码**。登录只走学校统一身份认证，本机仅保存加密后的登录令牌；退出登录会清除。
- **数据只在本机**：课表、专注记录、电费采样、校园卡流水都存在本机数据库，
  **没有自建服务器，也不会上传**。卸载 App 会一并清掉。
- **只读接口**：不做电费充值、不做校园卡转账、**不做自动抢座**；教室占用只显示"是否被占用"。
- **不采集**：没有统计 SDK、没有崩溃上报、没有广告。
- 仓库本身**不接收**任何把账号密码、令牌、个人房间号写进代码或文档的提交。


## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/`](docs/README.md) | 文档索引 |
| [`docs/interfaces/鉴权与登录契约.md`](docs/interfaces/鉴权与登录契约.md) | CAS / 教务 / 一卡通 / 校园卡 / 图书馆的登录方式与会话形态 |
| [`docs/interfaces/接口现状核查.md`](docs/interfaces/接口现状核查.md) | 逐个接口的实测结果（含字段、单位、坑） |
| [`docs/interfaces/接口实测坑.md`](docs/interfaces/接口实测坑.md) | 金额单位、必填字段、不存在的接口 |
| [`docs/interfaces/图书馆WebView灰屏.md`](docs/interfaces/图书馆WebView灰屏.md) | 图书馆网页在 WebView 里的机制、排除过程与有效修法 |
| [`docs/design/`](docs/design/) | 可行性与开发方案、Android 接入设计、UI 与登录设计、功能与工具清单 |




## 致谢

接口协议（CAS 登录、教务 EAS、一卡通）参考 / 对齐了以下社区项目，均为 MIT 许可：

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
