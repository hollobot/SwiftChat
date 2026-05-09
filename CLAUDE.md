# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 默认沟通与项目工作流

- 除非用户明确要求使用其他语言，否则对话使用中文。
- 进行开发项目时，除非用户明确要求，或当前说明明确要求，否则不要执行测试、构建或编译命令。
- 只格式化自己改动过的部分。除非用户明确要求，否则不要对未触碰的文件或整个项目运行大范围格式化工具。


## 项目概览

SwiftChat 是一个分布式桌面即时通讯应用，由两个独立模块组成：

- **`chat-service/`** — Java Spring Boot 后端（REST API + Netty WebSocket）
- **`chat-client-main/`** — Electron + Vue 3 桌面客户端

---

## 后端（chat-service）

**技术栈**：Java 8、Spring Boot 2.6.13、MyBatis Plus、MySQL、Redis/Redisson、Netty 4

### 常用命令

```bash
cd chat-service

# 编译验证（跳过测试）
mvn -q -DskipTests compile

# 打包
mvn -q -DskipTests package

# 运行开发服务
mvn spring-boot:run
```

> **注意**：Surefire 与 JUnit 5 配置存在不兼容，`mvn test` 可能返回 `Tests run: 0`，用 `compile` 验证即可。

### 分层架构

```
controller/       → REST 接口，入参校验
service/impl/     → 业务逻辑实现
mapper/           → MyBatis Plus 接口
entity/
  pojo/           → 数据库持久化对象
  dto/            → 请求入参
  vo/             → 响应出参
  enums/          → 业务枚举（含 ExceptionCodeEnum）
websocket/netty/  → Netty WebSocket 长连接服务
handler/          → 全局异常处理（GlobalExceptionHandler）
aspect/           → AOP 鉴权（@GlobalTokenInterceptor）
```

### 核心约定

- 所有接口返回 `ResultVo<T>` 统一包装
- 业务异常抛 `CustomException(ExceptionCodeEnum)`，由 `GlobalExceptionHandler` 统一处理
- 命名规范：`*Controller`、`*Service`、`*ServiceImpl`、`*Mapper`、`*Enum`
- 环境配置：`application.yml`（激活 dev）→ `application-dev.yml` / `application-prod.yml`
- 包前缀：`com.example`

### 认证机制

- 登录 `/account/login` → 生成 token，存 Redis `tokenInfo:{token}` → `TokenUserInfoDto`，同时存 `token:{userId}` → token（单会话强制，新登录使旧 token 失效）
- REST 请求：`@GlobalTokenInterceptor` AOP 从 Authorization header 读取 token → 校验 Redis
- WebSocket 连接：token 放在查询字符串 `ws://host/ws?token=JWT`（WS 协议不支持自定义 header），`ServerListenerHandler` 在 `HandshakeComplete` 事件中校验
- 用户 ID 通过 `AttributeKey<String> USER_ID_KEY` 绑定到 Netty Channel

### Netty Handler 管道

```
HttpServerCodec
HttpObjectAggregator(64KB)      → WebSocket 握手分块聚合
IdleStateHandler(读超时 6s)     → 超时自动断开（客户端心跳 5s 一次）
WebSocketServerProtocolHandler  → HTTP 升级为 WS，路径 /ws
ServerListenerHandler           → 业务消息处理
```

### WebSocket 消息类型（messageType）

| 值 | 类型 | 说明 |
|---|---|---|
| 0 | INIT | 连接建立后推送会话列表、消息、待处理申请数 |
| 1 | ADD_FRIEND | 好友请求消息 |
| 2 | CHAT | 文本聊天消息 |
| 3 | GROUP_CREATE | 群创建系统消息 |
| 4 | CONTACT_APPLY | 好友申请通知 |
| 5 | MEDIA_CHAT | 文件/图片/语音消息 |
| 6 | FILE_UPLOAD | 文件上传完成通知 |
| 7 | FORCE_OFF_LINE | 服务端强制下线（被踢） |
| 8-14 | GROUP_* | 群操作（加入、退出、移除成员、更新信息等） |
| 15 | VIDEO_CALL | WebRTC 视频通话信令 |
| 16 | USER_INFO_UPDATE | 好友信息更新 |
| 17 | VOICE_CALL | WebRTC 语音通话信令 |
| 18 | CALL_SYSTEM | 群通话状态 |

### 消息收发流程

**发送**：Vue → REST POST `/chat/send` → 后端存 MySQL → `ChannelContextUtils.sendMsg()` → 接收方在线则直接推 WS，不在线则存 Redis 离线队列。

**接收（重连后）**：`ChannelContextUtils.addContext()` 推送 INIT 消息，包含近 3 天消息记录 + Redis 离线队列（超 3 天丢弃）。

**关键设计**：消息通过 REST 发送（不是纯 WS），WS 仅用于服务端推送，解耦上传与通知。群消息广播时服务端排除发送者自身（防回显）。

### 联系人 ID 前缀约定

- `U` 开头 → 用户（`UserContactTypeEnum.USER`）
- `G` 开头 → 群组（`UserContactTypeEnum.GROUP`）

---

## 前端（chat-client-main）

**技术栈**：Electron 35、Vue 3（Composition API）、Vite、Pinia、Element Plus、SQLite、WebSocket

### 常用命令

```bash
cd chat-client-main

npm install          # 安装依赖

npm run dev          # 开发模式（启动 Electron + Vite 热更新）
npm run build        # 构建
npm run build:win    # 打包 Windows 可执行文件

npm run lint         # ESLint 检查
npm run format       # Prettier 格式化
```

### 进程架构

```
src/main/          → Electron 主进程
  index.js         → 入口
  ipc.js           → IPC 通道定义与处理
  wsClient.js      → WebSocket 客户端（连接后端 Netty）
  store.js         → electron-store 本地键值持久化
  database/        → SQLite 本地数据库操作（聊天记录缓存）

src/preload/       → 预加载脚本（桥接主进程与渲染进程）

src/renderer/src/  → Vue 应用
  views/           → 页面（登录、主聊天、群组等）
  components/      → 可复用组件
  stores/          → Pinia store（useUserInfoStore、useSessionStore 等）
  api/             → 按业务域拆分的 axios 请求模块
  router/          → Vue Router
  utils/api.js     → axios 实例与请求拦截器（鉴权、错误统一处理）
  constant/        → 常量定义
```

**数据流**：Vue 组件 → Pinia action → axios/IPC → 后端 REST；后端推送 → WebSocket → wsClient.js 解析 → IPC → renderer store → UI 刷新

### 核心约定

- 优先 `<script setup>` + Composition API
- Pinia store 导出为 `useXxxStore()`
- 路径别名 `@` 指向 `src/renderer/src`
- API 模块按业务域拆分：`userApi.js`、`chatApi.js` 等
- 代码格式：Prettier（`printWidth: 100`、双引号、Tab 缩进、CRLF）
- 异步逻辑显式 `try/catch`，禁止保留未使用导入

### 主要 IPC 通道

| 通道名 | 方向 | 用途 |
|---|---|---|
| `toMain` | renderer → main | 登录初始化，触发 WS 连接 |
| `reciveMessage` | main → renderer | 通用 WS 消息下发 |
| `loadChatMessage` | renderer → main | 分页从 SQLite 读取消息 |
| `addLocalMessage` | renderer → main | 消息本地持久化 |
| `localSessionData` | main ↔ renderer | 会话列表同步 |
| `webrtc:signal-message` | main ↔ renderer | P2P 视频信令 |
| `voicertc:signal-message` | main ↔ renderer | P2P 语音信令 |
| `groupvoicertc:signal-message` | main ↔ renderer | 群通话信令 |

### Pinia Store 职责

| Store | 管理内容 |
|---|---|
| `useUserInfoStore` | 当前用户信息（userId、email、nickName 等） |
| `useContactStore` | 好友列表、管理的群、加入的群 |
| `useApplyStore` | 好友申请 & 入群申请（待审批） |
| `useMessageCountStore` | 未读角标计数（chat/contact/group） |
| `useSysSettingStore` | 主题、本地文件路径、通知偏好 |
| `useAvatarUpdateStore` | 跨组件头像刷新触发 |

### SQLite 本地数据库

数据库路径：`%USERPROFILE%\chat-{dev|prod}\local.db`（按运行环境区分）

| 表 | 关键字段 | 用途 |
|---|---|---|
| `chat_message` | uuid(PK)、session_id、message_type、send_user_id、status(0=发送中,1=已发送) | 消息历史缓存 |
| `chat_session_user` | user_id+contact_id(复合PK)、session_id、last_message、no_read_count、top_type | 会话列表 |
| `user_setting` | user_id(PK)、sys_setting(JSON)、server_port | 用户本地配置 |

多账户支持：所有表含 `user_id`，切换账户即切换数据集，数据库文件共享。

### 通话架构

P2P 视频/语音通话使用 WebRTC 点对点信令（经后端中转），群通话同样是 P2P Mesh 模式（非 SFU/MCU），通过 `PeerConnectionDataDto`（含 groupId）标识群组上下文。通话窗口为独立 Electron 窗口，通过 `pageInitData` IPC 通道初始化状态。

### 本地文件存储路径

```
%USERPROFILE%\chat-{dev|prod}\{userId}\
  avatar\    → 头像缓存
  media\     → 聊天媒体文件
```
