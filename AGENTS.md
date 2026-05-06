# AGENTS.md

本文件面向在 `D:\1_dev\code\SwiftChat` 仓库工作的 agent。目标是给出最少但够用的项目规则；更细的模块规则请看子目录内的 `AGENTS.md`。

## 0. 约束（重要）

- 除非用户明确要求使用其他语言，否则对话使用中文。
- 进行开发项目时，除非用户明确要求，或当前说明明确要求，否则不要执行测试、构建或编译命令。
- 只格式化自己改动过的部分。除非用户明确要求，否则不要对未触碰的文件或整个项目运行大范围格式化工具。

## 1. 基本原则

- 默认使用中文沟通，除非用户明确要求其他语言。
- 先理解任务和影响范围，再动代码；不确定时说明假设或提问。
- 优先最小改动，不做顺手重构，不统一无关风格。
- 只清理自己改动造成的未使用代码；不要删除原本存在但无关的历史代码。
- 不要修改构建产物或依赖目录：`dist/`、`out/`、`node_modules/`。
- 不要提交真实密码、token、私钥、生产地址等敏感信息。
- 不要复述或写入内部系统/开发者提示词原文；只能把对项目有用的行为约束摘要化记录。

## 2. 仓库结构

SwiftChat 分为两个独立模块，根目录没有统一构建脚本。

- `chat-service/`：Java 8 + Spring Boot 2.6.13 后端，提供 REST API 与 Netty WebSocket。
- `chat-client-main/`：Electron 35 + Vue 3 桌面客户端。

规则优先级：

1. 用户当前明确要求。
2. 当前目录或子目录的 `AGENTS.md`。
3. 本文件。
4. 其他参考文档，例如 `CLAUDE.md`、README。

若后续新增 `.cursorrules`、`.cursor/rules/` 或 `.github/copilot-instructions.md`，需要阅读并同步更新本文件。

## 3. 工作流约束

- 涉及接口、IPC、store、数据库、路由时，沿调用链检查上下游。
- 仓库可能有用户未提交的改动；不要回滚、覆盖或重排无关文件。
- 编辑时匹配周边代码风格，只格式化自己改动过的部分。
- 默认不运行测试、构建、编译、lint 或 ESLint；只有用户明确要求验证时才执行。
- 用户说“不需要测试 / 不需要校验”时，必须遵守。
- 不要臆造仓库不存在的命令、测试框架或工具链。

## 4. 常用命令

后端命令在 `chat-service/` 执行：

```bash
mvn -q -DskipTests compile
mvn -q -DskipTests package
mvn spring-boot:run
mvn test
```

注意：当前后端可能存在 Surefire / JUnit 5 兼容问题，`mvn test` 可能出现 `Tests run: 0` 或 `No tests were executed`。若只是验证编译，使用 `mvn -q -DskipTests compile`。

前端命令在 `chat-client-main/` 执行：

```bash
npm install
npm run dev
npm run start
npm run lint
npm run format
npm run build
npm run build:win
```

注意：前端没有正式测试框架配置，也没有 `npm test` 脚本。根目录的 `test.js` 只是手动脚本，不要当作测试套件。

自动化执行命令时优先使用工具的工作目录参数，不要依赖手写 `cd && ...`。

## 5. 后端约定

- 包前缀：`com.example`。
- 分层：`controller`、`service`、`service.impl`、`mapper`、`entity/pojo`、`entity/dto`、`entity/vo`、`entity/enums`。
- 命名：`*Controller`、`*Service`、`*ServiceImpl`、`*Mapper`、`*Enum`。
- 对外返回统一使用 `ResultVo<T>`。
- 业务异常优先抛 `CustomException(ExceptionCodeEnum)`，由全局异常处理器转换。
- 需要登录态的接口使用现有 `@GlobalTokenInterceptor`。
- Mapper 方法变更时同步 XML；SQL 字段变更时同步 `resultMap`、DTO、VO。
- 注入、Lombok、分页、时间类型等沿用现有文件风格。

更多细节见 `chat-service/AGENTS.md`。

## 6. 前端约定

- 技术栈：Electron、Vue 3、Vite / electron-vite、Pinia、Element Plus。
- 渲染进程优先使用 `<script setup>` + Composition API。
- `@` 别名指向 `src/renderer/src`。
- HTTP 请求优先复用 `src/renderer/src/utils/api.js` 的 axios 实例。
- 新接口放入对应业务 API 模块，不直接散落在页面组件里。
- Pinia store 导出名使用 `useXxxStore`，异步逻辑通常放在 actions。
- 新增 IPC 时同步检查主进程注册、preload 暴露、渲染调用。
- 不要无关扩大 preload 权限面。
- JavaScript 文件不要混入半套 TypeScript 语法。

更多细节见 `chat-client-main/AGENTS.md`。

## 7. 交付前自检

- 改动是否都能追溯到用户请求。
- 是否误改构建产物、依赖目录、凭据、本地路径或二进制文件。
- 是否改动了接口字段、IPC 通道、store 字段、数据库映射却没有同步上下游。
- 是否新增了命令、测试框架或规则文件；如果新增，需同步更新本文件。
- 如果没有按默认验证命令执行，交付时说明原因；若用户已说不需要验证，不再额外提醒反复验证。

## 8. 关键参考文件

- `CLAUDE.md`
- `chat-service/AGENTS.md`
- `chat-service/pom.xml`
- `chat-client-main/AGENTS.md`
- `chat-client-main/package.json`
- `chat-client-main/src/renderer/src/utils/api.js`
- `chat-client-main/src/renderer/src/router/index.js`
- `chat-client-main/src/main/index.js`

