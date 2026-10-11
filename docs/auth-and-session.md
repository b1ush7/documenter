# 认证与会话设计（TODO 3.1）

> 适用范围：`/user/**` 全部认证相关接口。
> 对应 TODO 3.1 的四条落地项：双 Token 刷新流程、Refresh Token 传递方式、
> 多设备与会话撤销规则、验证码场景与渠道。

---

## 1. 双 Token 与刷新流程

### 1.1 Token 参数

| 项 | 值 | 配置位置 |
| --- | --- | --- |
| Access Token 有效期 | 15 分钟 | `res.jwt.access-token-ttl` |
| Refresh Token 有效期 | 7 天 | `res.jwt.refresh-token-ttl` |
| 签名算法 | HMAC256 | `JwtUtil` |
| 传递方式 | `Authorization: Bearer <accessToken>` | `res.jwt.authorization-name` |
| 会话标识 | JWT 的 `sid` 声明 | `JwtUtil` |

Access Token 与 Refresh Token 用 `token_type` 声明区分，**两者不可互换使用**：
Refresh Token 拿去访问受保护资源会被 `JwtUtil.verify` 拒绝（已有测试覆盖）。

### 1.2 登录

```
POST /user/login        { account, password }        → { accessToken, refreshToken, expiresIn, refreshExpiresIn }
POST /user/login/by-code { phone, code }             → 同上
POST /user/register      { username, phone?, password }
POST /user/register/by-code { username, phone, code, password }
GET  /user/me                                        → 当前用户信息（不含密码哈希）
```

登录内部调用 `JwtUtil.createTokenPair(userId)`，一次生成同一会话的两个 Token，
并把会话摘要写入 Redis（键 `login:session:<sid>`，值为 `userId:sha256(refreshToken)`）。

### 1.3 刷新（原子轮换）

```
POST /user/refresh   { refreshToken }   → 新的 { accessToken, refreshToken, ... }
```

关键性质：

- **不要求 Access Token 未过期**，只校验 Refresh Token，因此前端可以在 401 后直接续期。
- **旧 Refresh Token 立即失效**：轮换在 Redis 内用 Lua 原子完成
  （校验旧值 → 写入新值 → 续期），并发重放只有一个能成功。
- 同一 Refresh Token **不能重复使用**，否则等于没有轮换。

### 1.4 前端接入流程（约定的实现方式）

```text
请求任意受保护接口
  ├─ 200            → 正常处理
  ├─ 401 且 code=401（Token 无效/过期）
  │    ├─ 有 refreshToken → 调 /user/refresh
  │    │     ├─ 成功 → 覆盖本地双 Token，重放原请求（仅重放一次，避免死循环）
  │    │     └─ 失败（401「Refresh Token 已失效」）→ 清空本地 Token，跳登录页
  │    └─ 无 refreshToken → 跳登录页
  └─ 其他           → 按业务错误码提示
```

实现约束（避免常见坑）：

1. **刷新要加互斥**：多个请求同时 401 时只允许一个真正去刷新，其余等待结果，
   否则并发刷新会让彼此的 Refresh Token 互相作废（因为轮换是单次有效的）。
2. **只重放一次**：重放后仍 401 就直接跳登录页，防止无限循环。
3. **不要用 Access Token 的剩余时间做定时刷新**的单一策略：设备休眠、时钟漂移都会让它失准，
   以 401 驱动的刷新是可靠的主路径；定时刷新只能作为优化。
4. **跨标签页共享**：多个标签页各自持有一份 Token 会互相覆盖，
   建议用 `localStorage` + `storage` 事件同步，或把刷新收敛到 Service Worker。

> 后端已提供 `expiresIn` / `refreshExpiresIn`（秒），前端可据此做提前刷新；
> 但刷新失败后的兜底必须是「清 Token → 跳登录」，不能停留在半登录状态。

---

## 2. Refresh Token 的传递与保存方式

### 2.1 已选方案：JSON 返回 + 前端 localStorage

```
响应体：{ "accessToken": "...", "refreshToken": "...", "expiresIn": 900, "refreshExpiresIn": 604800 }
请求体：POST /user/refresh  { "refreshToken": "..." }
```

**选它的理由**

- 纯 REST、无状态，后端不需要处理 Cookie 域、SameSite、跨域携带等问题。
- 前后端分离部署（前端独立域名/端口）时不需要额外配置。
- 移动端 App、小程序同样能直接复用这套接口。

**它的代价（必须知情）**

- 存在 **XSS 风险**：任何能执行脚本的漏洞都能读走 Token。
  缓解手段在前端侧：严格转义、避免 `innerHTML`、配置 CSP、
  以及对第三方脚本做完整性校验（SRI）。
- 因此 **Access Token 有效期保持较短（15 分钟）**，
  万一泄露，暴露窗口有限。

### 2.2 备选方案：HttpOnly Cookie（未采用，但保留设计）

如果后续安全要求提高、或前端与后端同域部署，可切换到 Cookie 方案。切换时必须同时处理：

| 关注点 | 要求 |
| --- | --- |
| Cookie 属性 | `HttpOnly`（脚本不可读）、`Secure`（仅 HTTPS）、`SameSite=Lax` 或 `Strict` |
| **CSRF** | Cookie 会自动携带，因此**必须**重新启用 CSRF 防护：`SecurityConfig` 现在 `csrf(disable)` 需要改为 Cookie 仓库 + 前端读取 `XSRF-TOKEN` 并回填 `X-XSRF-TOKEN` 头 |
| 跨域部署 | 若前端与后端不同站，`SameSite=None` 且**必须** `Secure`，同时后端要允许携带凭据（`Access-Control-Allow-Credentials: true` 且 `Allow-Origin` 不能用 `*`） |
| 刷新接口的鉴权 | 刷新请求本身靠 Cookie，因此 `/user/refresh` 不能要求 `Authorization` 头 |
| 登出 | 必须服务端下发清除 Cookie 的响应头，仅前端删 `localStorage` 不够 |

**结论**：当前阶段采用 **localStorage 方案**，Cookie 方案作为同域部署时的可选升级路径。
切换的触发条件：出现同域部署需求，或安全评审要求 Token 不可被脚本读取。

> ⚠️ 无论哪种方案，**Refresh Token 都不得写入日志**。
> 现有代码只记录 `sid`，不打印 Token 内容。

---

## 3. 多设备登录与会话撤销规则

### 3.1 会话索引

Redis 中维护两份数据：

| 键 | 值 | TTL | 用途 |
| --- | --- | --- | --- |
| `login:session:<sid>` | `userId:sha256(refreshToken)` | 7 天（同 Refresh Token） | 单会话有效性；轮换的原子比较基准 |
| `login:user_sessions:<userId>` | Set，元素为 `<sid>` | 7 天 | **用户会话索引**，用于「退出全部设备」与撤销全部会话 |

- 每次签发（登录、刷新）都会把 `sid` 加入用户索引，并续期。
- 只删 `login:session:*` 而不同步索引，会导致索引里残留已失效的会话 ID，
  因此撤销逻辑**总是同时**删会话键并从索引移除。
- 索引 TTL 与会话一致，不会无限增长。

### 3.2 撤销规则表

| 场景 | 接口 | 行为 | 保留当前设备？ |
| --- | --- | --- | --- |
| 退出当前设备 | `POST /user/logout` | 删除当前 `sid` 的会话键 + 从索引移除 | 不适用（就是当前设备） |
| 退出全部**其他**设备 | `POST /user/logout-all` | 撤销除当前 `sid` 外的全部会话 | **是** |
| 修改密码 | `POST /user/change-password` | 校验原密码 → 写入新密码 → **撤销全部会话** | **否** |
| 找回密码 | `POST /user/reset-password` | 校验 RESET_PASSWORD 验证码 → 写入新密码 → **撤销全部会话** | **否** |
| 管理员禁用用户 | `P0-6 待实现` | 置 `status=DISABLED` + **撤销全部会话** + 审计 | 否 |
| 管理员恢复用户 | `P0-6 待实现` | 置 `status=ENABLED`；不恢复历史会话 | — |

**为什么改密要撤销「全部」会话而不是保留当前会话**

改密的动因通常是「怀疑账号已被他人使用」。若保留当前会话，
攻击者持有的旧会话仍能存活到 Access Token 过期为止，改密就没有起到切断访问的作用。
代价是用户改完密码需要重新登录一次——这个代价是值得的。

**禁用用户为什么要额外撤销会话**

`status` 只在**登录时**检查。如果不禁用时就撤销会话，
已登录的会话可以一直用到 Refresh Token 过期（最长 7 天），禁用形同虚设。

### 3.3 撤销实现的并发安全

`revokeAllSessions` 用 Lua 在 Redis 内执行：

```lua
-- ARGV[1] = userId，ARGV[2..] = 待撤销的 sid 列表
for i = 2, #ARGV do
  local current = redis.call('GET', KEYS[1] .. ARGV[i])
  if current and string.sub(current, 1, #userId + 1) == userId .. ':' then
    redis.call('DEL', KEYS[1] .. ARGV[i])
    redis.call('SREM', KEYS[2], ARGV[i])
  end
end
```

逐个校验会话的归属（值以 `userId:` 开头）后才删除，
因此即使用 `SMEMBERS` 取快照的那一刻有会话正在轮换，也不会误删**别的用户**的会话。
按用户隔离这一点已有测试覆盖。

### 3.4 已确认的会话行为

| 行为 | 说明 |
| --- | --- |
| 多设备登录 | 支持。每次登录生成独立 `sid`，互不影响 |
| 设备数量上限 | **未设置**。如需限制，建议在签发前检查索引大小并淘汰最早的会话 |
| 刷新后旧 Access Token | 仍可用（会话未变），直到自身过期 |
| 登出后 Access Token | **立即失效**（会话键被删，`getAuthenticatedUserId` 会拒绝） |

---

## 4. 验证码场景与渠道

### 4.1 场景划分

| 场景 | 枚举值 | 触发接口 | 前置校验 | 后续动作 |
| --- | --- | --- | --- | --- |
| 注册 | `REGISTER` | `POST /user/register/code` | 手机号**未**注册 | `POST /user/register/by-code` |
| 验证码登录 | `LOGIN` | `POST /user/login/code` | 手机号已注册且账号未禁用 | `POST /user/login/by-code` |
| 找回密码 | `RESET_PASSWORD` | `POST /user/reset-password/code` | 手机号已注册且账号未禁用 | `POST /user/reset-password` |

**用途隔离**：三个场景的 Redis 键与数据库记录都带 `scene`，
因此注册场景拿到的验证码**不能**用于重置密码，反之亦然。
这样即便攻击者诱导用户获取了注册验证码，也无法用于改密。

**预校验**：`POST /user/verify/code` 会真正消费验证码，供前端在提交前确认验证码有效。
注意它固定使用 `REGISTER` 场景。

### 4.2 渠道选择

| 项 | 决定 |
| --- | --- |
| P0 阶段 | 短信渠道，使用 `MockVerificationCodeSender`：验证码**只打印服务端日志**，不真正发送 |
| 渠道抽象 | `VerificationCodeSender` 接口，业务层只依赖接口 |
| 切换真实服务商 | 新增一个实现类并让其与 Mock 互斥（`res.verification.mock-enabled=false`） |
| 是否评估邮件 | 暂不接入。`VerificationChannel` 保留了 `EMAIL` 取值，但未实现 |

**为什么先评估短信**：现有 `user` 表已有 `phone` 字段且带唯一索引，
短信不需要新增用户信息采集；邮件则需要新增邮箱字段、验证流程与退信处理。

**接入真实短信前必须处理**：

1. **把「手机号是否已注册」的提示改成统一话术。**
   当前 `verifyScenePrecondition` 会明确区分「已注册 / 尚未注册」——
   这在开发期是必要的（否则分不清是发不出去还是账号不存在），
   但它**可被用来批量探测哪些手机号已注册**。接入真实短信后应统一回复
   「验证码已发送」。
2. **服务商失败处理**：当前实现会在投递失败时释放限流占位并返回 502，
   接入后需要补充服务商侧的重试与降级策略。
3. **密钥外置**：AppKey/Secret 走环境变量，不写进仓库。

### 4.3 频率与次数限制

| 限制 | 默认值 | 实现 |
| --- | --- | --- |
| 最短重发间隔 | 60 秒 | Redis `SET NX EX` 占位，天然抵御并发重复发送 |
| 日发送量 | 10 次 | Redis 原子「检查并自增」当天计数器（TTL 25 小时） |
| 校验错误次数 | 5 次 | Redis Hash 计数，超限即作废 |
| 验证码有效期 | 5 分钟 | Redis 键 TTL |

> 日限额**必须**用 Redis 计数而不是查数据库：
> `verification_record` 上 `(identifier, scene)` 是唯一索引，
> 重复发送只更新同一条记录，用 `count(*)` 永远只能数出 1，日限额会形同虚设。

---

## 5. 接口清单（P0 已完成部分）

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| POST | `/user/register` | 否 | 密码注册（手机号选填） |
| POST | `/user/register/by-code` | 否 | 验证码注册（手机号必填） |
| POST | `/user/register/code` | 否 | 发送注册验证码 |
| POST | `/user/login` | 否 | 密码登录 |
| POST | `/user/login/by-code` | 否 | 验证码登录 |
| POST | `/user/login/code` | 否 | 发送登录验证码 |
| POST | `/user/verify/code` | 否 | 预校验验证码（会消费） |
| POST | `/user/reset-password/code` | 否 | 发送找回密码验证码 |
| POST | `/user/reset-password` | 否 | 找回密码（成功后撤销全部会话） |
| POST | `/user/refresh` | 否 | 刷新双 Token（原子轮换） |
| GET | `/user/me` | 是 | 当前用户信息 |
| POST | `/user/change-password` | 是 | 修改密码（校验原密码，成功后撤销全部会话） |
| POST | `/user/logout` | 是 | 退出当前设备 |
| POST | `/user/logout-all` | 是 | 退出全部其他设备 |

**免认证路径维护注意**：`SecurityConfig` 的 `permitAll` 列表与
`JwtAuthenticationFilter.PUBLIC_PATHS` 是两处手工维护，**改一处必须同步另一处**，
否则会出现「SecurityConfig 放行但过滤器拦成 401」这类难以定位的现象。

---

## 6. 尚未完成

| 项 | 归属 |
| --- | --- |
| 管理员禁用/恢复用户并撤销其会话 | P0-6 |
| 模型配置管理（密钥不回传前端） | P0-6 |
| 真实短信服务商接入 | 待选型 |
| 设备数量上限与「查看登录设备列表」 | 未规划 |
| CSRF 防护 | 当前**已禁用**（`SecurityConfig` 中 `csrf(disable)`），这与「Refresh Token 走 JSON 而非 Cookie」是配套的；仅在改用 Cookie 方案时才需要重新启用 |
