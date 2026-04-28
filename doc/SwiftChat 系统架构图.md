### 需求描述
请基于以下SwiftChat即时通信系统的核心信息，生成专业、清晰的系统架构图（支持导出为可视化格式如PNG/SVG，同时补充架构图的文字说明）：

#### 系统核心信息
1. **系统整体组成**：桌面客户端、业务服务端、WebSocket实时通信服务、数据存储与缓存组件、后台管理模块
2. **架构分层**
   - 表现层：桌面客户端界面（Electron+Vue 3）、后台管理界面
   - 通信层：HTTP API（Axios）、WebSocket长连接（Netty/ws）、客户端IPC通信
   - 业务层：Spring Boot业务服务（账号、聊天、联系人、群聊、后台管理等）
   - 数据访问层：MyBatis-Plus/Mapper、SQLite本地数据库访问
   - 数据支撑层：MySQL（核心业务数据）、Redis（缓存/token/在线状态/离线消息）、SQLite（客户端本地数据）
3. **技术栈关键信息**
   - 客户端：Electron、Vue 3、Pinia、Element Plus、sqlite3、ws
   - 服务端：Spring Boot 2.6.13、Netty、MyBatis-Plus、MySQL、Redis、Redisson
4. **通信模式**：HTTP + WebSocket 双通道协同；客户端内部IPC通信（主进程/渲染进程）
5. **部署节点**：desktop-client、chat-service、netty-server、mysql、redis

#### 架构图要求
1. 分层清晰，体现“表现层-通信层-业务层-数据访问层-数据支撑层”五层结构
2. 标注各层核心技术、核心组件及交互关系
3. 体现客户端与服务端的通信链路（HTTP/WebSocket）、服务端与数据层的交互关系
4. 补充架构图说明，解释各层职责、核心组件作用及整体架构设计逻辑
5. 请你生成该系统的架构图，支持直接导入draw.io查看