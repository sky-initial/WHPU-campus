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

请求域名均为 `ecardapp.whpu.edu.cn`，HTTPS。上表只是官方 UI 实际请求，尚未验证第二页、末页及空结果。

卡信息候选字段：`account`（字符串）、`db_balance`（数值）、`unsettle_amount`（数值）、`acc_status`、`lostflag`、`freezeflag`、`barflag`、`cardtype`、`cardname`、`accinfo[]`。其他个人字段不纳入记录。

流水候选字段：`fromAccount`（字符串）、`fromJnNumber`（数值）、`jndatetime`、`jndatetimeStr`、`effectdateStr`、`tranamt`（数值）、`cardBalance`（数值）、`consumeType`、`resume`、`turnoverType`、`orderId`、`typeId`、`payName`、`locationName`。不从字段名推断金额单位、正负方向、时间语义或唯一键。

## 原生化前仍须确认

- 金额单位、余额显示计算方式、流水收支符号及展示字段。
- 分页排序、第二页与末页、空结果，完整结果的判断条件。
- 卡账号与流水归属是否一致；多卡及未绑定如何表示。
- 原生会话交接、过期及换账号行为；业务错误与未绑定的区别。

已有余额成功、流水成功和一次 401 证据；没有把这些等同于上述全部条件已完成。原生 Provider、Parser 与事务缓存应在必要语义核验后新增，继续阻断郑大请求。
