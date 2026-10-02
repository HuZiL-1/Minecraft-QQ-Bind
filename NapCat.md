# NapCat 正向 WebSocket 客户端开发文档

> 本文档面向 AI 或开发者，用于指导实现一个 NapCat 正向 WebSocket 客户端。
> 协议基于 OneBot v11，NapCat 作为 WebSocket 服务端，客户端主动连接。

## 1. 概述

NapCat 正向 WebSocket 模式下：

- NapCat 作为 **WebSocket 服务器**，监听指定端口。
- 应用端作为 **WebSocket 客户端**，主动连接 NapCat。
- 连接建立后，双方通过 **JSON 文本帧** 双向通信。
- 客户端可以接收 NapCat 推送的事件，也可以发送 API 请求调用 NapCat 功能。

推荐使用 WebSocket 而非 HTTP，因为实时性更好。

## 2. 连接与鉴权

### 2.1 在 NapCat 中启用正向 WS

在 NapCat WebUI 中：

1. 进入「网络配置」。
2. 点击「新建」→ 选择「WebSocket 服务器」。
3. 设置端口（默认 `3001`）。
4. 可选设置 `Access Token`，建议设置以保证安全。
5. 启用并保存。

对应配置文件示例（`config.yml`）：

```yaml
ws:
  servers:
    - url: ws://0.0.0.0:3001
      token: "your-token"   # 可选，建议设置
      enableHeart: true
```

### 2.2 客户端连接地址

```
ws://<NapCat主机>:3001
```

如果 NapCat 设置了 Token，客户端需要在连接时携带。支持两种方式：

**方式一：URL 查询参数**

```
ws://127.0.0.1:3001?access_token=your-token
```

**方式二：HTTP Header**

```
Authorization: Bearer your-token
```

### 2.3 连接参数建议

| 参数 | 建议值 | 说明 |
|---|---|---|
| 最大消息大小 | 1 GB | NapCat 默认 `max_size=2^30` |
| 打开超时 | 5 秒 | 连接超时 |
| 关闭超时 | 0.2 秒 | 关闭超时 |
| 心跳间隔 | 30 秒 | 客户端可主动发送 `get_status` 或等待心跳事件 |
| 重连策略 | 指数退避 | 最大 5 次，初始 1s，最大 30s |

## 3. 通信协议

### 3.1 消息帧格式

所有消息均为 **JSON 文本帧**。客户端收到的消息分为两类：

| 类型 | 特征 | 处理方式 |
|---|---|---|
| API 响应 | 包含 `echo` 字段 | 根据 `echo` 值匹配对应请求 |
| 事件推送 | 不含 `echo`，含 `post_type` | 转发给事件处理器 |

### 3.2 请求-响应模型

客户端发送 API 请求时，需构造如下 JSON：

```json
{
  "action": "send_group_msg",
  "params": {
    "group_id": 123456,
    "message": "大家好！"
  },
  "echo": "unique-request-id"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `action` | string | API 动作名称 |
| `params` | object | API 参数 |
| `echo` | string | 请求标识符，用于匹配响应，推荐 UUID |

NapCat 返回的响应格式：

```json
{
  "status": "ok",
  "retcode": 0,
  "data": {
    "message_id": 123
  },
  "echo": "unique-request-id"
}
```

**请求-响应配对建议：**

1. 生成 UUID 作为 `echo`。
2. 将 UUID 与一个异步 Future/Promise 关联，存入 Map。
3. 发送请求。
4. 收到含相同 `echo` 的响应时，解析结果并完成对应的 Future/Promise。
5. 若超时，拒绝该 Future/Promise。

## 4. 事件系统

### 4.1 事件通用字段

所有事件均包含以下基础字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `time` | int64 | 事件发生的时间戳 |
| `self_id` | int64 | 收到事件的机器人 QQ 号 |
| `post_type` | string | 事件类型：`message` / `notice` / `request` / `meta_event` |

### 4.2 事件类型

**消息事件（`post_type: "message"`）**

`message_type` 为 `private` 或 `group`。

私聊消息示例：

```json
{
  "post_type": "message",
  "message_type": "private",
  "user_id": 123456789,
  "message": "你好"
}
```

群聊消息示例：

```json
{
  "post_type": "message",
  "message_type": "group",
  "group_id": 123456,
  "user_id": 654321,
  "message": "你好"
}
```

**通知事件（`post_type: "notice"`）**

包含群成员变动、戳一戳、群文件上传等。`notice_type` 区分具体类型。

**请求事件（`post_type: "request"`）**

好友申请、群邀请等，通常需要调用 API 进行同意/拒绝操作。

**元事件（`post_type: "meta_event"`）**

包括 `heartbeat`（心跳）和 `lifecycle`（生命周期）。

### 4.3 事件分发策略

建议按以下顺序路由：

1. 检查是否有 `echo` 字段，有则作为 API 响应处理。
2. 否则按 `post_type` 一级路由。
3. 再按 `message_type` / `notice_type` / `request_type` 二级路由。
4. 事件类型可使用点分格式表示（如 `message.group`、`message.private`），便于类型过滤。

## 5. API 调用

### 5.1 常用 API 接口

| 接口 | 用途 | 关键参数 |
|---|---|---|
| `send_group_msg` | 发送群消息 | `group_id`, `message` |
| `send_private_msg` | 发送私聊消息 | `user_id`, `message` |
| `send_msg` | 通用发送（自动判断类型） | `message_type`, `user_id`/`group_id`, `message` |
| `get_group_list` | 获取群列表 | — |
| `get_friend_list` | 获取好友列表 | — |
| `get_group_member_info` | 获取群成员信息 | `group_id`, `user_id` |
| `get_login_info` | 获取登录号信息 | — |
| `get_status` | 获取运行状态 | — |
| `set_group_ban` | 群禁言 | `group_id`, `user_id`, `duration` |
| `delete_msg` | 撤回消息 | `message_id` |

NapCat 实现了 OneBot v11 的大部分 API 及大量扩展接口，完整列表参考官方 API 文档。

### 5.2 消息段格式

消息内容支持字符串或消息段数组。消息段数组用于复杂消息：

```json
{
  "action": "send_group_msg",
  "params": {
    "group_id": 123456,
    "message": [
      { "type": "text", "data": { "text": "你好" } },
      { "type": "image", "data": { "file": "file:///path/to/image.png" } },
      { "type": "at", "data": { "qq": "123456789" } }
    ]
  },
  "echo": "req-001"
}
```

常用消息段类型：

| 类型 | 说明 | data 字段 |
|---|---|---|
| `text` | 纯文本 | `text` |
| `image` | 图片 | `file`（支持 URL、Base64、文件路径） |
| `record` | 语音 | `file` |
| `face` | QQ 表情 | `id` |
| `at` | @某人 | `qq` |
| `reply` | 回复消息 | `id` |

## 6. 心跳与重连

### 6.1 心跳

NapCat 会定期推送 `meta_event.heartbeat`，客户端应监听该事件以判断连接存活。

客户端也可以主动发送 `get_status` 请求来检测连接。

### 6.2 重连策略

建议实现指数退避重连：

```python
MAX_RECONNECT_ATTEMPTS = 5
INITIAL_BACKOFF = 1.0   # 秒
MAX_BACKOFF = 30.0      # 秒
```

重连流程：

1. 连接断开后，等待 `INITIAL_BACKOFF`。
2. 重连失败则等待时间翻倍，直到 `MAX_BACKOFF`。
3. 达到最大重连次数后停止或报警。
4. 重连成功后重置退避时间。

## 7. Python 客户端完整示例

以下示例使用 `websockets` 库，实现连接、鉴权、请求-响应配对、事件分发、心跳与重连。

```python
import asyncio
import json
import uuid
import logging
from typing import Dict, Any, Callable, Optional

import websockets
from websockets.exceptions import ConnectionClosed

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("napcat-client")


class NapCatClient:
    def __init__(
        self,
        uri: str = "ws://127.0.0.1:3001",
        token: Optional[str] = None,
        reconnect: bool = True,
    ):
        self.uri = uri
        self.token = token
        self.reconnect = reconnect
        self.ws: Optional[websockets.WebSocketClientProtocol] = None
        self._pending: Dict[str, asyncio.Future] = {}
        self._event_handlers: Dict[str, Callable] = {}
        self._running = False

    async def connect(self):
        """建立连接并开始消息循环"""
        self._running = True
        backoff = 1.0
        attempts = 0

        while self._running:
            try:
                headers = {}
                if self.token:
                    headers["Authorization"] = f"Bearer {self.token}"

                self.ws = await websockets.connect(
                    self.uri,
                    extra_headers=headers,
                    max_size=2 ** 30,
                    open_timeout=5,
                    close_timeout=0.2,
                )
                logger.info("已连接到 NapCat")
                attempts = 0
                backoff = 1.0

                await self._message_loop()

            except (ConnectionClosed, OSError) as e:
                logger.warning(f"连接断开: {e}")

            if not self.reconnect or not self._running:
                break

            attempts += 1
            if attempts > 5:
                logger.error("达到最大重连次数，停止重连")
                break

            logger.info(f"等待 {backoff:.1f} 秒后重连...")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 30.0)

    async def _message_loop(self):
        """接收并分发消息"""
        async for raw in self.ws:
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                logger.error(f"无效 JSON: {raw}")
                continue

            if "echo" in data:
                # API 响应
                echo = data["echo"]
                future = self._pending.pop(echo, None)
                if future and not future.done():
                    future.set_result(data)
            else:
                # 事件推送
                await self._dispatch_event(data)

    async def _dispatch_event(self, event: dict):
        """事件分发"""
        post_type = event.get("post_type", "")
        # 构建点分事件类型
        if post_type == "message":
            event_type = f"message.{event.get('message_type', 'unknown')}"
        elif post_type == "notice":
            event_type = f"notice.{event.get('notice_type', 'unknown')}"
        elif post_type == "request":
            event_type = f"request.{event.get('request_type', 'unknown')}"
        elif post_type == "meta_event":
            event_type = f"meta_event.{event.get('meta_event_type', 'unknown')}"
        else:
            event_type = post_type

        # 调用注册的处理器
        handler = self._event_handlers.get(event_type)
        if handler:
            try:
                if asyncio.iscoroutinefunction(handler):
                    await handler(event)
                else:
                    handler(event)
            except Exception as e:
                logger.exception(f"事件处理器异常: {e}")

        # 通用处理器
        general = self._event_handlers.get("*")
        if general:
            try:
                if asyncio.iscoroutinefunction(general):
                    await general(event)
                else:
                    general(event)
            except Exception as e:
                logger.exception(f"通用事件处理器异常: {e}")

    def on(self, event_type: str, handler: Callable):
        """注册事件处理器，event_type 如 'message.group'，'*' 表示所有事件"""
        self._event_handlers[event_type] = handler

    async def call_api(self, action: str, params: dict, timeout: float = 10.0) -> dict:
        """发送 API 请求并等待响应"""
        if not self.ws:
            raise RuntimeError("未连接")

        echo = str(uuid.uuid4())
        future = asyncio.get_event_loop().create_future()
        self._pending[echo] = future

        payload = {
            "action": action,
            "params": params,
            "echo": echo,
        }
        await self.ws.send(json.dumps(payload))

        try:
            result = await asyncio.wait_for(future, timeout=timeout)
            return result
        except asyncio.TimeoutError:
            self._pending.pop(echo, None)
            raise TimeoutError(f"API 请求超时: {action}")

    async def close(self):
        """关闭连接"""
        self._running = False
        if self.ws:
            await self.ws.close()
            self.ws = None


# ---------- 使用示例 ----------

async def main():
    client = NapCatClient(
        uri="ws://127.0.0.1:3001",
        token="your-token",  # 如果设置了 Token
    )

    @client.on("message.group")
    async def on_group_message(event: dict):
        group_id = event["group_id"]
        user_id = event["user_id"]
        message = event["message"]
        logger.info(f"收到群 {group_id} 中 {user_id} 的消息: {message}")

        # 回复消息
        await client.call_api("send_group_msg", {
            "group_id": group_id,
            "message": "收到！"
        })

    @client.on("meta_event.heartbeat")
    def on_heartbeat(event: dict):
        logger.debug("心跳正常")

    # 启动连接（会阻塞）
    await client.connect()


if __name__ == "__main__":
    asyncio.run(main())
```

## 8. 开发注意事项

1. **鉴权**：如果 NapCat 设置了 Token，必须携带，否则连接会被拒绝。
2. **echo 唯一性**：每次请求使用 UUID，避免并发请求冲突。
3. **超时处理**：API 请求设置合理超时（建议 10 秒），超时后清理 pending。
4. **事件处理异步化**：事件处理器中如果执行耗时操作，不要阻塞消息循环，可使用 `asyncio.create_task`。
5. **重连后状态恢复**：重连成功后，可能需要重新获取群列表、好友列表等状态。
6. **消息段格式**：发送复杂消息时使用消息段数组，注意 `data` 字段结构。
7. **日志**：记录连接、断开、API 请求、事件处理等关键日志，便于排查。
8. **资源清理**：关闭时取消所有 pending Future，避免内存泄漏。

## 9. 参考

- OneBot v11 标准：https://github.com/botuniverse/onebot-11
- NapCat 官方文档：https://napcat.napneko.icu/