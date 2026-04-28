# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

**数据流**：Vue 组件 → Pinia action → axios/IPC → 后端 REST；后端推送 → WebSocket → store → UI 刷新

### 核心约定

- 优先 `<script setup>` + Composition API
- Pinia store 导出为 `useXxxStore()`
- 路径别名 `@` 指向 `src/renderer/src`
- API 模块按业务域拆分：`userApi.js`、`chatApi.js` 等
- 代码格式：Prettier（`printWidth: 100`、双引号、Tab 缩进、CRLF）
- 异步逻辑显式 `try/catch`，禁止保留未使用导入
