# SwiftChat

基于 Spring Boot、Netty、Electron 和 Vue 3 的桌面即时通讯系统，包含聊天服务端与桌面客户端两个独立模块。

## 项目结构

- `chat-service/`：Java Spring Boot 后端，提供 REST API 与 Netty WebSocket 服务
- `chat-client-main/`：Electron + Vue 3 桌面客户端

## 技术栈

### 后端
- Java 8
- Spring Boot 2.6.13
- MyBatis-Plus
- MySQL
- Redis / Redisson
- Netty 4.1.84.Final
- Knife4j / Swagger

### 前端
- Electron 35
- Vue 3
- Vite
- Pinia
- Element Plus
- SQLite
- Axios

## 快速开始

### 1. 启动后端

工作目录：`chat-service/`

```bash
mvn -q -DskipTests compile
mvn spring-boot:run
```

后端本地运行依赖：
- MySQL
- Redis

### 2. 启动前端

工作目录：`chat-client-main/`

```bash
npm install
npm run dev
```

## 常用命令

### chat-service

```bash
mvn -q -DskipTests compile
mvn -q -DskipTests package
mvn spring-boot:run
java -jar target/chat-service-0.0.1-SNAPSHOT.jar
```

### chat-client-main

```bash
npm install
npm run dev
npm run start
npm run lint
npm run format
npm run build
npm run build:unpack
npm run build:win
npm run build:mac
npm run build:linux
```

## 目录说明

### 后端目录
- `chat-service/src/main/java/com/example/controller/`：REST 控制层
- `chat-service/src/main/java/com/example/service/`：Service 接口
- `chat-service/src/main/java/com/example/service/impl/`：Service 实现
- `chat-service/src/main/java/com/example/mapper/`：Mapper 层
- `chat-service/src/main/java/com/example/entity/`：DTO / VO / POJO / 枚举
- `chat-service/src/main/resources/mapper/`：MyBatis XML

### 前端目录
- `chat-client-main/src/main/`：Electron 主进程
- `chat-client-main/src/preload/`：预加载桥接
- `chat-client-main/src/renderer/src/views/`：页面
- `chat-client-main/src/renderer/src/components/`：组件
- `chat-client-main/src/renderer/src/stores/`：Pinia store
- `chat-client-main/src/renderer/src/api/`：业务 API 模块
- `chat-client-main/src/main/database/`：SQLite 相关逻辑

## 开发说明

- 根目录没有统一构建脚本，请进入对应子项目执行命令
- 前后端为独立模块，联调时需分别启动
- 前端当前没有正式测试框架配置
- 后端测试可能受 Surefire / JUnit 兼容问题影响，编译验证优先使用 `mvn -q -DskipTests compile`

## 相关文档

- `chat-service/README.md`
- `chat-client-main/README.md`

## 许可证

当前仓库子项目 README 中均注明为非商业使用场景，使用前请分别阅读对应模块说明。