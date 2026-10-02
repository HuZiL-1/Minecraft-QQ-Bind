# Minecraft QQ Bind

一个**纯服务端**的 Fabric 模组:通过 QQ 群把 Minecraft 玩家账号与 QQ 号绑定,
未绑定的玩家会被拒绝进入服务器。

底层通过 [NapCat](https://napcat.napneko.icu/) 的正向 WebSocket(OneBot v11)与 QQ 通信。

> ### 🤖 创作声明
>
> **本项目由 AI 创作(vibe coding)。**
>
> 全部代码由 AI([Claude Code](https://claude.com/claude-code))生成。人类负责提出需求、
> 设定约束、提供测试环境并做最终验收——包括在真实服务端上跑通「未绑定被踢出 → 群内
> 发码绑定 → 重新进服通行」的完整链路。
>
> 请在生产环境部署前自行审阅代码,尤其是鉴权、输入校验与数据存储部分。

## 工作原理

1. 玩家进入服务器时,模组按 UUID 查询 SQLite 数据库。
2. **已绑定** → 正常进入。
3. **未绑定** → 立即断开连接,玩家在断开界面看到:

   ```
   你还没绑定QQ号,请到QQ群 123456789 发送 /绑定 A1B2C3 完成绑定!
   ```

   其中 `A1B2C3` 是一次性验证码(默认 6 位、10 分钟有效)。玩家反复重连时会复用
   同一个未过期的验证码。
4. 玩家在指定 QQ 群发送 `/绑定 A1B2C3`,NapCat 推送群消息给模组,校验通过后写入
   绑定关系,并在群里回复结果。
5. 玩家再次进入服务器即可通行。

## 环境要求

| 项目 | 版本 |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | ≥ 0.19.5 |
| Fabric API | 0.161.0+26.2 |
| Java | ≥ 25 |

**只要把编译好的 jar 丢进服务端的 `mods/` 目录即可**,SQLite 驱动已经打包在 jar 内
(jar-in-jar),服务器端无需安装任何额外东西。

## 安装与配置

1. 把 jar 放进服务端 `mods/`。
2. 启动一次服务器,会生成 `config/mc_qq_mod.json`。
3. 修改配置(至少填好 `napcat.url`、`napcat.accessToken`、`binding.groupIds`)。
4. 在 NapCat 的 WebUI 中启用「正向 WebSocket 服务器」,端口与配置一致。
5. 重启服务器,或用 `/qqmod reload` 重载配置(连接参数变更需重启)。

### 配置文件说明

```jsonc
{
  "napcat": {
    "url": "ws://127.0.0.1:3001",   // NapCat 正向 WS 地址
    "accessToken": "",              // 与 NapCat 中设置的一致,留空表示不鉴权
    "requestTimeoutSeconds": 10,    // 单个 API 请求超时
    "heartbeatTimeoutSeconds": 90,  // 超过该时长没收到心跳就主动重连
    "maxReconnectBackoffSeconds": 30
  },
  "binding": {
    "groupIds": [],                 // 允许绑定的群号;留空表示不限群
    "commands": ["/绑定", "/bind"], // 绑定指令前缀,可多个
    "codeLength": 6,
    "codeExpireSeconds": 600,
    "maxAttemptsPerMinute": 5,      // 每个 QQ 每分钟验证码尝试上限(防爆破)
    "maxAccountsPerQq": 1,          // 一个 QQ 最多能绑几个游戏账号;0 = 不限
    "replyInGroup": true,           // 是否在群里回复绑定结果
    "adminPermissionLevel": 2       // /qqmod 指令所需权限等级(0-4)
  },
  "messages": {
    // 可用的占位符:{group} {prefix} {code} {player} {qq}
    "kick": "你还没绑定QQ号,请到QQ群 {group} 发送 {prefix} {code} 完成绑定!"
  },
  "storage": {
    "file": "config/mc_qq_mod/bindings.db"
  }
}
```

> `maxAccountsPerQq` 默认是 `1`,即「一个 QQ 只能绑一个游戏账号」。
> 改成 `N` 表示一个 QQ 最多绑 N 个账号(适合小号较多的玩家),`0` 表示不限。
>
> 反方向没有限制:同一个游戏账号可以绑定多个 QQ。

## 指令

仅限权限等级达到 `adminPermissionLevel`(默认 2)的玩家:

| 指令 | 作用 |
|---|---|
| `/qqmod query <玩家>` | 查询玩家绑定的 QQ |
| `/qqmod unbind <玩家>` | 解绑玩家(群内不能自助解绑) |
| `/qqmod status` | 查看 NapCat 连接状态 |
| `/qqmod reload` | 重载配置文件 |

## 安全说明

- **Access Token 属于敏感信息**,`config/mc_qq_mod.json` 不要提交到版本库。
- 验证码有有效期与尝试频率限制,降低被暴力猜解的风险。
- 绑定只会在 `binding.groupIds` 列出的群里被受理。

## 从源码构建

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

产物在 `build/libs/mc_qq_mod-<version>.jar`。

## 许可

CC0-1.0
