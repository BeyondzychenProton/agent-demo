import { readFileSync } from "node:fs";
import { delimiter, join, resolve } from "node:path";
import { spawn } from "node:child_process";

// 独立脚本模型后端只服务自动化测试；依赖 Maven 预先生成测试类与类路径。
const root = resolve("../ag-ui");
const dependencies = readFileSync(join(root, "target/test-classpath.txt"), "utf8").trim();
const executable = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
const child = spawn(executable, ["-cp", [join(root, "target/test-classes"), join(root, "target/classes"), dependencies].join(delimiter),
  "com.beyondzy.demo.readmodel.ReadModelTestServer"], { cwd: root, stdio: "inherit", windowsHide: true });
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => child.kill(signal));
child.on("exit", code => process.exit(code ?? 1));
