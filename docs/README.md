# 文档索引

当前 WHPU 功能、认证与接口依据优先查看下方的 [WHPU 接入记录](#whpu-新接入记录)。[发布与安装](whpu-release.md)列出已发布版本，[后续路线图](whpu-roadmap.md)列出剩余验收条件。

`interfaces/` 与 `design/` 保留原 ZZUPocket 的郑大接口调查和设计资料，不作为 WHPU 的接口、金额单位或登录依据。各份验证记录对应其注明的版本和时间；历史测试结果不等于当前版本重新通过。

## interfaces/ — 原郑大接口历史资料

| 文档 | 内容 |
| --- | --- |
| [接口现状核查](interfaces/接口现状核查.md) | 原郑大 CAS / 教务 EAS / 一卡通 / 图书馆 / 电费的接口清单与历史响应 |
| [本科课表接口](interfaces/eas-schedule-api.md) | 教务 course-table 的数据形状、lessonType 枚举、「含实验」开关（issue #7 根因）、教学周权威接口、学校客户端数据面；与 [gms-schedule-api](gms-schedule-api.md)（研究生）并列 |
| [鉴权与登录契约](interfaces/鉴权与登录契约.md) | 每个系统怎么登录、令牌是什么形态、会话怎么保存 |
| [接口实测坑](interfaces/接口实测坑.md) | 实测踩到的坑：金额单位、必填字段、看起来存在其实不存在的接口…… |
| [图书馆 WebView 灰屏](interfaces/图书馆WebView灰屏.md) | 内嵌学校网页"整片灰底"的成因、排查过程与有效修法 |

## design/ — 原项目设计与方案

| 文档 | 内容 |
| --- | --- |
| [可行性与开发方案](design/可行性与开发方案.md) | 最早那版可行性分析：能做什么、要对什么接口、**哪些方案后来被否掉了**（例如"WebView 复用 cookie"） |
| [Android 接入设计](design/Android接入设计.md) | 网络层、会话隔离、数据流与模块划分 |
| [UI 与登录设计](design/UI与登录设计.md) | 页面结构、登录流程与状态呈现 |
| [功能与工具清单](design/功能与工具清单.md) | 每个功能用到哪些接口、哪些纯本地数据 |
| [开发流程与阶段计划](开发流程与阶段计划.md) | 阶段划分（P1…P6）与每阶段的验收口径 |

## 不在本仓库里的东西（以及为什么）

- **原始抓包与认证资料**：密码、Cookie、Token 和个人数据不入库。WHPU 的测试 Fixture 使用人工或脱敏数据，接口请求以对应 WHPU 核验记录为依据。
- **本机构建/装机的环境笔记**：含设备序列号，不入库。通用部分见根目录 `README.md` 的「构建」一节。

## WHPU 新接入记录

- [发布与安装](whpu-release.md)：当前候选版 rc13、覆盖升级方式及验证边界。
- [后续路线图](whpu-roadmap.md)：已交付功能、接口证据和待外部条件验收的事项。
- [rc11 发布验收](whpu-rc11-validation.md)：校园网页错误修复、最终主线构建、正式签名升级及发布附件回验。
- [rc12 发布验收](whpu-rc12-validation.md)：三个电费分组原生查询、旧缓存兼容、最终签名包覆盖升级及附件回验。
- [rc13 发布验收](whpu-rc13-validation.md)：电费大字体弹窗修复、最终主线构建、正式 rc12 覆盖升级与最终包显示检查。

- [考试安排](whpu-exams.md)：学校请求证据、字段映射、完整分页与离线缓存。

- [折叠布局与上课免打扰](whpu-class-dnd.md)：系统授权、真实课表调度、规则隔离及验证范围。

- [考试日常视图](whpu-exams-daily.md)：今日考试、桌面混排、当前学期缓存与验证范围。
- [自定义上课作息](whpu-period-settings.md)：按学期设置时分、日常视图和免打扰联动、周次强调及验证范围。

- [rc3 发布验收](whpu-rc3-validation.md)：正式签名覆盖升级、离线缓存、手机与平板布局、已知验证边界。
- [成绩与考试学期记忆](whpu-query-terms.md)：独立保存查询选择、离线恢复、今日与组件入口的当前学期定位。

- [校园服务网页入口](whpu-campus-services.md)：已核对入口、网页认证边界、原生查询前置条件。

- [校园卡接口核验](whpu-campus-card-evidence.md)
- [电费接口核验](whpu-electricity-evidence.md)：三个分组的目录、查询字段及单位依据。
- [电费原生查询](whpu-electricity-native.md)：缓存兼容、精度、今日页与小组件验证。
- [rc4 发布验收](whpu-rc4-validation.md)
