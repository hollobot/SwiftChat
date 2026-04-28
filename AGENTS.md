# AGENTS.md

本文件面向在 `D:\1_dev\code\SwiftChat` 仓库内工作的 agent。
目标：快速对齐构建/运行/测试命令、单测运行方式、代码风格、分层约定与工作边界。

## 1. 仓库概览

SwiftChat 由两个独立模块组成：

- `chat-service/`：Java Spring Boot 后端，提供 REST API 与 Netty WebSocket。
- `chat-client-main/`：Electron + Vue 3 桌面客户端。

根目录目前没有统一构建脚本；实际命令分别在两个子项目内执行。

## 2. 规则文件检查结果

已检查以下路径：

- `.cursorrules`
- `.cursor/rules/`
- `.github/copilot-instructions.md`

当前仓库未发现上述 Cursor / Copilot 规则文件。

已发现的 agent 规则来源：

- `CLAUDE.md`
- `chat-client-main/AGENTS.md`
- `chat-service/AGENTS.md`
- 本文件 `AGENTS.md`

若后续新增 Cursor / Copilot 规则，应视为高优先级约束，并同步更新本文件。

## 3. 目录结构

### 3.1 后端 `chat-service/`

- `src/main/java/com/example/controller/`：REST 控制层
- `src/main/java/com/example/service/`：Service 接口
- `src/main/java/com/example/service/impl/`：Service 实现
- `src/main/java/com/example/mapper/`：MyBatis / MyBatis-Plus Mapper
- `src/main/java/com/example/entity/pojo/`：持久化对象
- `src/main/java/com/example/entity/dto/`：请求对象
- `src/main/java/com/example/entity/vo/`：响应对象
- `src/main/java/com/example/entity/enums/`：业务枚举
- `src/main/java/com/example/handler/`：全局异常处理
- `src/main/java/com/example/aspect/`：AOP 与鉴权
- `src/main/resources/mapper/`：Mapper XML
- `src/test/java/`：测试代码

### 3.2 前端 `chat-client-main/`

- `src/main/`：Electron 主进程
- `src/preload/`：预加载桥接
- `src/renderer/src/views/`：页面
- `src/renderer/src/components/`：复用组件
- `src/renderer/src/stores/`：Pinia store
- `src/renderer/src/api/`：按业务域拆分的 API 模块
- `src/renderer/src/router/`：路由
- `src/renderer/src/utils/api.js`：axios 实例与请求拦截器
- `src/main/database/`：SQLite 相关逻辑
- `build/`、`resources/`：构建与资源文件
- `dist/`、`out/`：构建产物，不要手改

## 4. 工作方式

- 先判断改动落在哪一层：后端、主进程、预加载、渲染进程。
- 优先最小改动，不顺手重构无关代码。
- 涉及接口、IPC、store、数据库、路由时，要沿调用链检查上下游。
- 不要编辑构建产物目录：`dist/`、`out/`、`node_modules/`。
- 不要臆造仓库不存在的命令、测试框架或工具链。
- 默认不跑测试、lint、build、compile；只有用户明确要求验证时才执行对应命令。
- 若用户明确说“不需要测试 / 不需要校验”，后续 agent 必须遵守，不要再次主动执行。
- 若新增命令、测试框架、规则文件，必须同步更新本文件。

## 5. 根目录常用操作

从仓库根目录切换到对应子项目执行命令：

```bash
# 后端
cd chat-service

# 前端
cd chat-client-main
```

如果用自动化工具执行命令，优先通过工作目录参数切换，不要依赖手写 `cd && ...`。

## 6. 后端构建 / 运行 / 测试命令

工作目录：`chat-service/`

### 6.1 构建与运行

```bash
mvn -q -DskipTests compile
mvn -q -DskipTests package
mvn spring-boot:run
java -jar target/chat-service-0.0.1-SNAPSHOT.jar
```

说明：

- `compile` 是当前最可靠的后端基础验证命令。
- `package` 可用于确认打包链路是否正常。
- 本地运行依赖 MySQL、Redis 与 dev 配置。

### 6.2 测试命令

```bash
mvn test
mvn -Dtest=ChatServiceApplicationTests test
mvn -Dtest=ChatServiceApplicationTests#contextLoads test
mvn -Dtest=ChatServiceApplicationTests#contextLoads test -DfailIfNoTests=false
```

说明：

- 全量测试：`mvn test`
- 单个测试类：`mvn -Dtest=类名 test`
- 单个测试方法：`mvn -Dtest=类名#方法名 test`
- 当前仓库存在 Surefire / JUnit 5 兼容问题，可能出现 `Tests run: 0` 或 `No tests were executed`。
- 当目标只是验证代码未编译坏，优先执行：`mvn -q -DskipTests compile`
- 当单测因 0 条执行而失败，可临时使用：`-DfailIfNoTests=false`

### 6.3 后端 lint / formatter 现状

当前未发现以下独立配置：

- Checkstyle
- Spotless
- PMD
- SpotBugs

因此后端没有单独 lint 命令；实际硬性校验以编译通过为主，测试可跑时再补充测试验证。

## 7. 前端构建 / 运行 / 测试命令

工作目录：`chat-client-main/`

### 7.1 安装 / 开发 / 构建

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

说明：

- `npm run dev`：Electron + Vite 开发模式
- `npm run start`：预览构建产物
- `npm run lint`：ESLint 检查
- `npm run format`：Prettier 全仓格式化
- `npm run build`：构建
- `build:*`：平台打包

### 7.2 测试现状

当前前端没有正式测试框架配置：

- `package.json` 没有 `test` 脚本
- 未发现 Vitest / Jest / Playwright / Cypress 配置
- 没有标准测试目录约定

存在的仅是手动脚本：

```bash
node test.js
```

这个脚本不是正式测试套件，不要把它当作 `npm test` 替代品。

### 7.3 单个测试怎么跑

当前前端没有可复用的“单个测试文件 / 单个测试用例”命令。
不要虚构：

- `npm test`
- `npx vitest`
- `npx jest`

除非未来仓库明确引入对应测试框架并更新本文件。

## 8. 默认验证策略

当任务完成后，按改动所在模块做最小必要验证：

### 8.1 改后端代码

优先执行：

```bash
mvn -q -DskipTests compile
```

若任务涉及测试类或用户明确要求测试，再尝试：

```bash
mvn test
```

或目标单测命令。

### 8.2 改前端代码

优先执行：

```bash
npm run lint
```

若改动影响打包入口、Electron 主进程、预加载、构建配置，再执行：

```bash
npm run build
```

### 8.3 只改文档

通常不需要构建，但要检查：

- 路径是否正确
- 命令是否可执行
- 模块归属是否写对

## 9. 后端代码风格

### 9.1 技术与版本

- Java 版本：1.8
- Spring Boot：2.6.13
- 包前缀：`com.example`
- 已使用 Lombok

### 9.2 命名与分层

- Controller：`*Controller`
- Service 接口：`*Service`
- Service 实现：`*ServiceImpl`
- Mapper：`*Mapper`
- 枚举：`*Enum`
- DTO / VO / POJO 职责分离，不混用
- Mapper XML 与接口保持同名、同职责

### 9.3 导入规则

- 不使用通配符导入
- import 分组：JDK / `javax` -> 第三方 -> 项目内
- 保持显式 import
- 单个文件内依赖风格保持一致

### 9.4 依赖注入与常用工具

- 注入方式优先沿用 `@Resource`
- 已使用 Lombok 的模型类优先复用 `@Data`、`@Slf4j` 等
- 未要求时不要切换整套注入风格或 Lombok 风格

### 9.5 返回模型与类型约定

- 对外统一返回 `ResultVo<T>`
- 优先复用统一的 `success` / `error` 风格
- 时间字段优先沿用现有 `Timestamp`
- 状态优先使用枚举，不写魔法数字
- 分页沿用 `PageHelper.startPage` + `PageInfo<T>`

### 9.6 控制层 / Service / Mapper 约定

- 控制层负责接参与轻转换
- 业务逻辑放 Service，不要堆在 Controller
- 需要登录态的接口加 `@GlobalTokenInterceptor`
- 入参校验优先 `@Validated` + `javax.validation`
- Mapper 多参数用 `@Param`
- 改 Mapper 方法时同步修改 XML
- 改 SQL 字段时检查 `resultMap`、VO、DTO 是否同步

### 9.7 异常处理

- 业务异常优先抛 `CustomException(ExceptionCodeEnum)`
- 统一由 `GlobalExceptionHandler` 转成 `ResultVo`
- 参数错误、鉴权失败、权限不足应返回明确错误码
- 不要吞异常
- 记录日志时应包含足够上下文

### 9.8 格式化与可读性

- 4 空格缩进
- 不使用 Tab
- 方法按“参数校验 -> 业务处理 -> 返回”组织
- 仅保留必要注释
- 用空行分隔逻辑阶段，避免长段混杂逻辑

### 9.9 配置与安全

- 配置文件：`application.yml`、`application-dev.yml`、`application-prod.yml`
- 默认激活 dev
- 不提交真实密码、token、私钥、生产地址
- 新增配置项至少在一个 profile 中完整落地

## 10. 前端代码风格

### 10.1 技术栈与模块边界

- Electron 35
- Vue 3，优先 Composition API
- Vite / electron-vite
- Pinia
- Element Plus
- 代码主体是 JavaScript，不是 TypeScript

### 10.2 Prettier / ESLint / EditorConfig

已确认配置来源：

- `chat-client-main/.prettierrc.yaml`
- `chat-client-main/eslint.config.mjs`
- `chat-client-main/.editorconfig`

Prettier 规则：

- `printWidth: 100`
- `tabWidth: 4`
- `useTabs: true`
- `semi: true`
- `singleQuote: false`
- `trailingComma: none`
- `bracketSpacing: true`
- `arrowParens: always`
- `endOfLine: crlf`
- Vue 文件使用 `parser: vue`

ESLint 规则补充：

- 基于 `@electron-toolkit/eslint-config`
- 启用 `eslint-plugin-vue`
- 关闭 `vue/require-default-prop`
- 关闭 `vue/multi-word-component-names`

注意：

- `.editorconfig` 要求 2 空格和 LF
- Prettier 要求 Tab 和 CRLF
- 实际修改时以仓库已有 Prettier 结果和周边文件风格为准
- 不要只因换行符或缩进风格制造大 diff

### 10.3 Vue / 组件约定

- 优先 `<script setup>` + Composition API
- 常见样式为 `<style lang="scss" scoped>`
- 表单、弹窗、提示优先复用 Element Plus
- 懒加载路由沿用 `() => import("@/views/...")`
- 不要在同一文件混入完全不同的组件组织方式

### 10.4 导入规则

- 渲染进程优先使用 `@` 别名，映射到 `src/renderer/src`
- 导入顺序建议：框架库 -> 第三方 -> `@/` -> 相对路径 -> 样式
- 不保留未使用导入
- 主进程存在 ESM 与 CommonJS 混用历史，修改时优先延续文件现有风格
- 不要无故把整个 CommonJS 文件改成 ESM，或反过来

### 10.5 命名规则

- Pinia store 导出名：`useXxxStore`
- API 文件：`camelCase + Api`，如 `userApi.js`
- 工具函数文件：`camelCase.js`
- 普通变量、函数、方法：`camelCase`
- Vue 组件文件名以所在目录现有模式为准，仓库同时存在 `PascalCase.vue` 与 `camelCase.vue`
- 路由 path 保持局部一致，不做全局式改名

### 10.6 状态管理与数据流

- Store 保留最少必要状态
- 异步逻辑通常放在 actions
- 更新 store 优先通过 action
- 若状态同步到本地存储或 Electron store，修改时检查同步逻辑
- HTTP 请求优先复用 `src/renderer/src/utils/api.js` 的 axios 实例
- 新接口放到对应业务 API 模块，不直接散落在页面中
- 默认返回结构很多地方依赖 `data.data`、`data.code`、`data.message`

### 10.7 IPC 与 Electron

- 渲染进程访问桌面能力优先通过已有桥接
- 新增 IPC 时同时检查：主进程注册、preload 暴露、渲染调用
- 涉及窗口控制、托盘、文件系统、数据库时优先放主进程
- 不要无关扩大 preload 权限面

### 10.8 错误处理

- 异步逻辑显式 `try/catch`
- 尤其是 IPC、文件系统、网络请求、数据库操作
- 不要静默吞错
- 对用户可恢复失败优先给出 Element Plus 消息提示
- 控制台日志只保留必要上下文，不要刷屏
- 若函数兜底返回，保持返回值形状稳定
- 可空数据先判空再访问

### 10.9 类型与数据约束

- 不要在 `.js` 文件中引入半套 TypeScript 语法
- 虽然没有静态类型系统，也要保持接口响应、store state、IPC payload 结构稳定
- 做字段重命名或映射时用显式转换
- 不依赖隐式魔法

## 11. 注释策略

- 仅在关键业务规则、平台差异、IPC 行为、数据库映射处写必要注释
- 不要写显而易见的注释
- 新增注释优先简洁中文
- 未被要求时不要额外补大量注释

## 12. 修改边界

- 只改当前任务需要的模块和调用链
- 不顺手统一整个仓库风格
- 历史代码若有命名、模块风格、格式不一致，除非任务相关，不扩大修复范围
- 发现范围外明显问题时，不直接顺手改
- 修改大文件时避免全文件重排

## 13. 提交前最小检查清单

### 13.1 后端改动

```bash
mvn -q -DskipTests compile
```

如涉及测试，再补充目标测试命令，并记录是否受 Surefire / JUnit 问题影响。

### 13.2 前端改动

```bash
npm run lint
```

如影响构建入口或打包，再补充：

```bash
npm run build
```

### 13.3 通用检查

- 是否误改凭据、本地路径、二进制文件
- 是否误改构建产物
- 是否改动了接口字段、IPC 通道、store 字段、数据库映射而未同步上下游
- 是否需要更新本文件中的命令或规则说明

## 14. 禁止事项

- 不要假设前端存在正式测试套件
- 不要虚构单测命令
- 不要修改 `dist/`、`out/`、`node_modules/`
- 不要引入新的包管理器锁文件
- 不要无确认地重命名接口字段、IPC 通道、store 字段、数据库字段映射
- 不要提交真实敏感信息

## 15. 关键参考文件

- `CLAUDE.md`
- `chat-service/pom.xml`
- `chat-service/README.md`
- `chat-service/AGENTS.md`
- `chat-client-main/package.json`
- `chat-client-main/README.md`
- `chat-client-main/AGENTS.md`
- `chat-client-main/eslint.config.mjs`
- `chat-client-main/.prettierrc.yaml`
- `chat-client-main/.editorconfig`
- `chat-client-main/src/renderer/src/utils/api.js`
- `chat-client-main/src/renderer/src/router/index.js`
- `chat-client-main/src/main/index.js`


## 约束
- 不需要跑测试
- 不需要 ESLint 校验