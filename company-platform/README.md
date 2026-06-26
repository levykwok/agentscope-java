# 公司主业务模块（company-platform）

这个目录是你们的主平台代码工作区，和 `agentscope-*` 框架仓库代码解耦放置：

- `src/main/java/com/company/platform`：主业务代码
- `src/main/resources`：配置、静态资源、模板
- `runtime/`：运行态目录（数据库/会话/日志/临时文件等）
- `pom.xml`：引入 framework 依赖（`agentscope-harness`）

## 当前已提供
- Spring Boot 启动入口：`com.company.platform.PlatformApplication`
- 默认运行端口：8080
- 默认 workspace：`${user.home}/.company-platform`
- 可覆盖运行目录环境变量：`COMPANY_PLATFORM_WORKSPACE`

## 运行方式
```bash
mvn -pl company-platform -am clean package -DskipTests
java -jar company-platform/target/company-platform-*.jar
```

后续你们的业务功能建议放到：
- `com.company.platform.agent`：Agent 组装与编排
- `com.company.platform.web`：HTTP/API 控制层
- `com.company.platform.service`：业务服务
- `com.company.platform.config`：配置与 Bean 组装
