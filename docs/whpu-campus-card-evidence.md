# WHPU 校园卡接口核验（未开始原生接入）

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

请求域名均为 `ecardapp.whpu.edu.cn`，HTTPS。通过官方账单页滚动已验证 `current=2&size=8`，响应页码为 2、页大小为 8、返回 8 条，`pages` 与 `total` 一致。末页及空结果尚未核验。

卡信息候选字段：`account`（字符串）、`db_balance`（数值）、`unsettle_amount`（数值）、`acc_status`、`lostflag`、`freezeflag`、`barflag`、`cardtype`、`cardname`、`accinfo[]`。其他个人字段不纳入记录。

流水候选字段：`fromAccount`（字符串）、`fromJnNumber`（数值）、`jndatetime`、`jndatetimeStr`、`effectdateStr`、`tranamt`（数值）、`cardBalance`（数值）、`consumeType`、`resume`、`turnoverType`、`orderId`、`typeId`、`payName`、`locationName`。不从字段名推断金额单位、正负方向、时间语义或唯一键。

## 原生化前仍须确认

- 余额显示计算方式、流水方向字段的完整取值及唯一标识范围。
- 分页排序、末页、空结果，完整结果的判断条件。
- 卡账号与流水归属是否一致；多卡及未绑定如何表示。
- 原生会话交接、过期及换账号行为；业务错误与未绑定的区别。

已有余额成功、流水成功和一次 401 证据；没有把这些等同于上述全部条件已完成。原生 Provider、Parser 与事务缓存应在必要语义核验后新增，继续阻断郑大请求。

## 官方前端映射证据

只读检查平板已加载的学校静态 JavaScript，不读取认证值，不保存脚本中的运行时个人数据。

- `/campus-card/js/chunk-2d215c68.a7f41737.js`：账单金额使用 `tranamt / 100`，保留两位小数；`typeFrom === "1"` 显示 `+`，其他值显示 `-`。本次第二页观察到方向值 `1`、`2`，金额均非负。不能沿用旧实现按原始金额正负推断消费类型，也不能把所有未知方向值当成已知支出类型。
- 同一脚本：列表时间优先使用 `effectdateStr`，否则使用 `jndatetimeStr`；第二页显示时间均符合 `yyyy-MM-dd HH:mm:ss`。脚本会比较两者并标记延迟，不能把两字段视为完全相同语义。
- 同一脚本：收入、支出汇总同样除以 100；列表按响应 `pages` 停止翻页，详情导航使用 `orderId`。第二页 `orderId` 非空且页内无重复，尚不足以证明跨页、跨账号或多卡全局唯一。
- `/campus-card/js/app.5d4bb620.js`：通用请求拦截器从应用状态的 `token`、`token_type` 组装 `synjones-auth`，类型缺省为 `bearer`；初始状态取自对应 sessionStorage 项。这里只确认静态来源，尚未实现或验收原生会话交接。

## 复用代码审查与接入约束

Dot 只读审查后已核对现有实现：`CardTransactionEntity` 仅以 `journo` 为主键，DAO 为全表查询/清理；`CardBalanceStore` 只保存一份余额与时间，无账号或卡归属。旧 `CardRepository` 仍绑定郑大 `EcardClient`，逐页写入且达到页数上限直接返回，不能直接用于 WHPU。

WHPU 接入时必须满足：

- 流水、余额和成功时间使用同一学校来源／账号／卡账号分区；旧无归属缓存不自动归给当前账号。
- 完整拉取并校验分页后在单个 Room 事务内提交；失败、取消或截断保留旧快照；提交前再次核对会话代次与归属。
- 原始坏金额和时间报解析失败，不回退为 0 元或 1970 年；领域统计只接收已核验的规范化数据。
- 今日页、小组件和统计查询一起传递分区；小组件继续只读缓存。

必要回归包括双账号同流水号、同账号双卡、第二页 401、页数上限、刷新中换号、完整空结果、坏金额/时间以及旧无归属缓存升级。尚未新增 Room schema 或启用原生校园卡入口。
