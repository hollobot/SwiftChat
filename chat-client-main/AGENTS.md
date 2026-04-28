# AGENTS

## 项目概览

- 项目是 Electron + Vue 3 桌面聊天客户端，构建工具为 `electron-vite`。
- 包管理器使用 `npm`，锁文件为 `package-lock.json`。
- 代码主要分为三层：`src/main` 主进程、`src/preload` 预加载、`src/renderer/src` 渲染进程。
- 渲染进程使用 Vue 3、Pinia、Vue Router、Element Plus。
- 当前仓库没有正式测试框架配置；没有 `npm test`、Vitest、Jest、Playwright 或 Cypress 脚本。

## 规则来源

- 已检查根目录 `AGENTS.md`：创建前不存在。
- 已检查 `.cursorrules`：不存在。
- 已检查 `.cursor/rules/`：不存在。
- 已检查 `.github/copilot-instructions.md`：不存在。
- 因此本文件即为当前仓库给 agent 使用的主要执行规则。

## 目录约定

- `src/main/`：Electron 主进程、IPC、数据库、本地文件、窗口管理。
- `src/preload/`：预加载桥接层。
- `src/renderer/src/`：Vue 页面、组件、Pinia store、API 封装、工具函数、样式资源。
- `resources/`：Electron 图标等资源。
- `build/`：构建相关资源。
- `dist/`、`out/`：构建产物，默认不要手改。

## 常用命令

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

## 测试现状

- 当前 `package.json` 没有 `test` 脚本。
- 当前仓库没有发现标准单元测试目录或测试运行器配置。
- 根目录存在一个手动脚本 `test.js`，它不是测试框架用例，只是本地请求脚本。
- 因此 agent 不要臆造 `npm test`、`vitest`、`jest` 之类命令。

## 单个测试怎么跑

- 目前没有可复用的“单个测试”命令，因为仓库没有正式测试框架。
- 若只是运行现有手动脚本，可执行：

```bash
node test.js
```

- 如果后续新增测试框架，先更新本文件，再补充“跑整个测试集”“跑单个测试文件”“跑单个用例”的精确命令。

## Agent 工作流程

- 改动前先判断影响层：主进程、预加载、渲染进程，避免跨层误改。
- 优先做最小改动，不要顺手重构无关代码。
- 修改 API、IPC、store、路由时，要沿调用链检查上下游是否同步。
- 改动完成后，至少运行与改动最接近的验证命令；常见为 `npm run lint`，必要时再跑 `npm run build`。
- 不要编辑 `dist/`、`out/`、`node_modules/` 里的文件。

## 格式化与基础风格

- 以 Prettier 配置为准：`printWidth: 100`、`tabWidth: 4`、`useTabs: true`、`semi: true`、`singleQuote: false`、`trailingComma: none`。
- Prettier 还要求 `endOfLine: crlf`，但仓库内历史文件存在不一致；优先保持被修改文件的现有换行风格，不要只为换行符制造大 diff。
- `.editorconfig` 与 Prettier 存在冲突；实际提交时以仓库的 Prettier 结果和周边文件风格为准。
- Vue 文件通常使用 `<script setup>`，样式常见为 `<style lang="scss" scoped>`。
- 不要因为格式化顺手重排整个大文件，除非本次任务明确要求。

## 导入规则

- 优先使用现有别名 `@`，其映射到 `src/renderer/src`。
- 渲染进程导入顺序建议：框架库 -> 第三方库 -> `@/` 别名模块 -> 相对路径 -> 纯样式导入。
- 主进程文件优先延续文件内现有模块风格；仓库里存在 ESM `import` 与 CommonJS `require` 混用的历史代码，未经要求不要大规模统一。
- 新增渲染进程模块时，优先使用现有 ESM 写法。
- 不保留未使用导入；提交前清理明显无用的 import。

## 命名约定

- Vue 组件文件名以现有风格为准，仓库同时存在 `PascalCase.vue` 与 `camelCase.vue`；新增文件时优先贴合所在目录已有模式。
- Pinia store 导出名使用 `useXxxStore`。
- API 模块按业务域拆分，文件名通常为 `userApi.js`、`groupApi.js` 这类 `camelCase + Api`。
- 工具函数文件使用 `camelCase.js`，常量文件使用语义化名称。
- 普通变量、函数、方法、响应式引用使用 `camelCase`。
- 路由 path 使用现有小写或驼峰混合风格时，保持局部一致，不要一次性全局改名。

## Vue 与前端约定

- 组件优先使用 Composition API 与 `<script setup>`。
- 组件状态通常使用 `ref`/`reactive`；沿用现有页面的写法，不要在同一文件里混入完全不同的组织方式。
- 表单、提示、消息反馈优先复用 Element Plus 组件与 `ElMessage`。
- 样式优先使用局部 `scss`；只有确实需要全局覆盖时再放非 `scoped` 样式。
- 使用路由懒加载时，沿用 `() => import("@/views/...")` 形式。

## 状态管理约定

- Store 中保留最少必要状态，异步获取逻辑通常放在 `actions`。
- 更新 store 时优先通过 action，而不是在外部任意改内部结构。
- 若 store 数据同时落地本地存储，修改时要检查 `localStorage`、`sessionStorage` 或 Electron store 的同步逻辑。

## API 与数据流约定

- 渲染进程 HTTP 请求统一优先走 `src/renderer/src/utils/api.js` 中的 axios 实例。
- 新增接口优先放到对应业务域 API 文件，不要把请求直接散落进页面组件。
- 复用现有返回结构约定：很多调用点默认读 `data.data`、`data.code`、`data.message`。
- 修改接口调用前，先确认拦截器是否已经处理了鉴权、重复请求、统一错误提示。

## IPC 与 Electron 约定

- 渲染进程访问桌面能力优先通过 `window.ipcRenderer` 调用已有通道。
- 新增 IPC 时，需要同时检查主进程注册点和渲染进程调用点。
- 预加载层目前较薄；如果新增暴露能力，尽量延续当前桥接模式，不要无关扩大权限面。
- 涉及窗口控制、托盘、文件系统、数据库时，优先在主进程处理。

## 错误处理约定

- 异步逻辑优先显式 `try/catch`，尤其是 IPC、文件系统、网络请求、数据库操作。
- 不要静默吞错；至少记录日志，必要时给用户可见提示。
- 已有代码里存在 `console.log`/`console.error` 调试输出，新增日志应聚焦上下文，不要刷屏。
- 若函数故意兜底返回，保持返回值形状稳定，避免让上层出现 `undefined` 崩溃。
- 对用户可恢复的失败，优先给出 Element Plus 消息提示；对开发排障信息，再写控制台日志。

## 类型与数据约束

- 仓库当前主体是 JavaScript，不是 TypeScript。
- 新代码不要引入半套 TS 语法到 `.js` 文件。
- 虽无静态类型系统，也要保持数据结构稳定，尤其是接口响应、store state、IPC payload。
- 对对象字段重命名或映射时，沿用现有显式转换写法，避免隐式魔法。
- 对可空数据先判空再访问；仓库里大量代码依赖 `data.data == null` 这类守卫。

## 注释约定

- 仅在业务规则不直观、IPC 行为、数据库字段映射、平台差异逻辑处补充简短注释。
- 不要写解释性废话注释，也不要为显而易见的赋值逐行注释。
- 保留已有中文注释语境，新增注释优先简洁中文。

## 修改策略

- 只改当前任务需要的模块和调用链，不顺手修整个仓库风格问题。
- 历史代码存在命名拼写问题、格式不统一、模块风格混杂；除非任务直接相关，否则不要扩大修复范围。
- 若发现明显 bug 但不在任务范围，最多在交付说明里点出，不直接顺带改。
- 修改大文件时避免全文件重新排版，尽量让 diff 聚焦业务变更。

## 提交前检查

- 先看改动是否触达构建入口、路由、store、IPC、数据库、文件系统。
- 运行 `npm run lint`；若改动影响打包或 Electron 入口，再运行 `npm run build`。
- 若只改文档，通常不需要构建，但要检查 Markdown 路径和命令是否准确。
- 若新增可执行命令、测试命令、规则文件，务必同步更新本文件。

## 禁止事项

- 不要假设存在测试套件。
- 不要改动构建产物目录。
- 不要引入新的包管理器文件，如 `pnpm-lock.yaml`、`yarn.lock`。
- 不要在无需求时把 CommonJS 文件整体改写成 ESM，或反过来。
- 不要在没有确认调用链的情况下重命名 IPC 通道、store 字段、接口字段。

## 参考文件

- `package.json`
- `eslint.config.mjs`
- `.prettierrc.yaml`
- `.editorconfig`
- `jsconfig.json`
- `electron.vite.config.mjs`
- `src/renderer/src/utils/api.js`
- `src/renderer/src/router/index.js`
- `src/renderer/src/stores/userInfoStore.js`
- `src/main/index.js`
