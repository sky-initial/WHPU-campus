# WHPU 校园卡接口核验与原生请求基础层

来源：2026-11-14（北京时间）OPD2407 调试版 WebView 的实际学校页面请求。仅记录接口结构，不保存认证值、账号、余额或交易内容。学校页面操作均为只读查询。

## 认证与入口

融合门户 `https://e.whpu.edu.cn/` → 服务中心 → 虚拟校园卡 → `ecardapp.whpu.edu.cn`。学校前端请求使用 `synjones-auth` 头。本次账单曾返回 HTTP 401 并回到学校登录页；经官方门户重新交接后，同一账单接口返回 HTTP 200、`code=200`、`success=true`。尚未核验 WebView 向原生交接的来源与失效边界，不复制或硬编码令牌。

## 已观察到的查询

| 用途 | 方法与路径 | 查询参数 | 成功结构 |
| --- | --- | --- | --- |
| 卡信息 | GET `/berserker-app/ykt/tsm/queryCard` | `synAccessSource=h5` | `code`、`success`、`data.retcode`、`data.card[]`、`msg` |
| 流水 | GET `/berserker-search/search/personal/turnover` | `size=8&current=1&synAccessSource=h5` | `data.records[]`、`data.total`、`data.size`、`data.current`、`data.pages` |
| 流水类别 | GET `/berserker-search/search/turnoverType` | `synAccessSource=h5` | `data[]` 含 `id`、`name`、`icon`、`flag` 等 |
| 统计 | GET `/berserker-search/statistics/turnover/count` | `timeFrom`、`timeTo`（日期字符串）、`synAccessSource=h5` | `data.income`、`data.expenses` |
| 当前用户归属 | GET `/berserker-base/user` | `synAccessSource=h5` | `data.id`（数值）、`data.account`、`data.cardAccount` |
| 余额显示配置 | GET `/berserker-app/frontInfo` | `synAccessSource=h5` | `data.getEcardConfig`（JSON 字符串），包含 `type` |

请求域名均为 `ecardapp.whpu.edu.cn`，HTTPS。通过官方账单页正常滚动已核验第二页至末页，页码、页大小、总页数与总条数一致，末页为短页；页面最终显示完整列表并停止加载。当前完整结果内 `orderId` 无重复、显示时间倒序、流水卡账号一致。学校页面请求客户端使用已确认的搜索参数 `info` 查询人工无匹配文本，返回 `current=1,size=8,pages=0,total=0,records=[]`。不保留真实交易内容。

卡信息字段：`account`（字符串）、`db_balance`（数值）、`unsettle_amount`（数值）、`elec_accamt`（数值）、`acc_status`、`lostflag`、`freezeflag`、`barflag`、`cardtype`、`cardname`、`accinfo[]`。其他个人字段不纳入记录。成功卡查询本次为 `retcode="0"`；缺少卡数组、非零返回码不能作为成功空卡结果。

流水候选字段：`fromAccount`（字符串）、`fromJnNumber`（数值）、`jndatetime`、`jndatetimeStr`、`effectdateStr`、`tranamt`（数值）、`cardBalance`（数值）、`consumeType`、`resume`、`turnoverType`、`orderId`、`typeId`、`payName`、`locationName`。不从字段名推断金额单位、正负方向、时间语义或唯一键。

## 原生化前仍须确认

- 方向字段未见取值、其他余额配置及多账号/多卡真实场景。
- 实际未绑定账号的返回；目前只观察到有效卡，以及官方前端处理空卡数组的分支。
- 原生会话交接、过期及换账号行为；业务错误与未绑定的区别。

已有余额、完整流水、空搜索、资料与配置成功和一次 401 证据。已据此实现原生 Provider、Parser 和分页收集，但尚未接入 WebView 认证交接、Room 缓存或界面，不宣称原生校园卡业务已完成；郑大请求继续阻断。

## 官方前端映射证据

只读检查平板已加载的学校静态 JavaScript，不读取认证值，不保存脚本中的运行时个人数据。

- `/campus-card/js/chunk-2d215c68.a7f41737.js`：账单金额使用 `tranamt / 100`，保留两位小数；`typeFrom === "1"` 显示 `+`，其他值显示 `-`。本次第二页观察到方向值 `1`、`2`，金额均非负。不能沿用旧实现按原始金额正负推断消费类型，也不能把所有未知方向值当成已知支出类型。
- 同一脚本：列表时间优先使用 `effectdateStr`，否则使用 `jndatetimeStr`；第二页显示时间均符合 `yyyy-MM-dd HH:mm:ss`。脚本会比较两者并标记延迟，不能把两字段视为完全相同语义。
- 同一脚本：收入、支出汇总同样除以 100；列表按响应 `pages` 停止翻页，详情导航使用 `orderId`。第二页 `orderId` 非空且页内无重复，尚不足以证明跨页、跨账号或多卡全局唯一。
- `/campus-card/js/app.5d4bb620.js`：通用请求拦截器从应用状态的 `token`、`token_type` 组装 `synjones-auth`，类型缺省为 `bearer`；初始状态取自对应 sessionStorage 项。这里只确认静态来源，尚未实现或验收原生会话交接。
- `/plat/js/chunk-2a9d726a.5fa5b6ba.js`：配置 `type="1"` 时余额使用 `elec_accamt / 100`；`type="2"` 时使用 `(db_balance + unsettle_amount) / 100`；其他类型的前端分支相加两者。实际学校配置和配置接口均核验为 `1`，原生层仅支持已有明确分支的 `1`、`2`，其他配置报不支持，不猜测具体配置含义。
- `/plat/js/app.c323c028.js`：当前用户使用 GET `/berserker-base/user`，配置使用 GET `/berserker-app/frontInfo`。通过学校请求客户端核验资料 `id` 与当前页面一致；流水 `fromAccount`、卡 `account` 与资料 `cardAccount` 在本账号相符，只记录比较结果，不保存标识值。

## 复用代码审查与接入约束

Dot 只读审查后已核对现有实现：`CardTransactionEntity` 仅以 `journo` 为主键，DAO 为全表查询/清理；`CardBalanceStore` 只保存一份余额与时间，无账号或卡归属。旧 `CardRepository` 仍绑定郑大 `EcardClient`，逐页写入且达到页数上限直接返回，不能直接用于 WHPU。

WHPU 接入时必须满足：

- 流水、余额和成功时间使用同一学校来源／账号／卡账号分区；旧无归属缓存不自动归给当前账号。
- 完整拉取并校验分页后在单个 Room 事务内提交；失败、取消或截断保留旧快照；提交前再次核对会话代次与归属。
- 原始坏金额和时间报解析失败，不回退为 0 元或 1970 年；领域统计只接收已核验的规范化数据。
- 今日页、小组件和统计查询一起传递分区；小组件继续只读缓存。

必要回归包括双账号同流水号、同账号双卡、第二页 401、页数上限、刷新中换号、完整空结果、坏金额/时间以及旧无归属缓存升级。尚未新增 Room schema 或启用原生校园卡入口。

## 原生基础层实现状态

- `WhpuCardProvider` 仅请求上述已核验的只读接口；认证值保留在内存，放入请求头，不放入 URL；不跟随重定向，不复用旧 CAS 客户端。
- `WhpuCardParser` 使用整数分保存金额，按已见方向值 `1`/`2` 转换收支，使用严格日期解析和 `Asia/Shanghai`。坏值报错，不写入零值占位；仅保留展示和归属必需字段。
- 分页严格核对请求页码、页大小、页总数、记录总数、每页条数和同一卡内重复订单。达到页数上限、后页失败或取消均不返回部分快照。
- 查询前后核对校园卡用户身份，每次请求核对会话代次；结果携带代次，后续缓存提交仍需在事务边界再次校验。
- 基础层使用人工 Fixture 测试，不读取测试机凭据。Room 分区迁移、事务缓存、页面会话交接、原生 UI、今日页和小组件适配仍待完成。

本轮验证：新增 27 项校园卡单元测试（16 项解析/分页、11 项请求/会话），全项目 534 项单元测试通过，失败、错误、跳过均为 0；Debug APK 构建成功。未将本轮基础层构建安装到实体平板，保留现有登录页面；原生网络会话交接仍需实现后验收。
