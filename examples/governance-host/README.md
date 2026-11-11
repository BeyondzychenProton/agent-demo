# 最小治理宿主

使用 JDK 21。在 `ag-ui` 目录编译适配器并生成运行所需的依赖路径。通过环境变量 `GOVERNANCE_BASE_URL` 和 `GOVERNANCE_AGENT_TOKEN` 配置当前环境。

```powershell
mvn -q -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/governance-host-classpath.txt"
$governanceHostClasspath = "target/classes;" + (Get-Content target/governance-host-classpath.txt -Raw).Trim()
java -cp $governanceHostClasspath ../examples/governance-host/MinimalGovernanceHost.java
```

追加工具名和 JSON 对象即可执行该工具。以下调用要求对应 Skill 和资源已授权给应用。

```powershell
java -cp $governanceHostClasspath ../examples/governance-host/MinimalGovernanceHost.java read_governance_skill_resource '{"skillId":"meeting-booking","path":"references/room-directory.md"}'
```

宿主入口调用一次 `AgentScopeGovernanceAdapter.attach(baseUrl, token)`，取得 Toolkit 和 Skill 指令。工具执行使用治理端授予的应用身份。需要可信用户身份的工具由治理端按其策略校验。
