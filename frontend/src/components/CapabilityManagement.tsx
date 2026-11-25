import { AgentSkillBinding } from "./AgentSkillBinding";
import { SkillAdmin } from "./SkillAdmin";
import { McpManagement } from "./McpManagement";
type Kind = "Skills 管理" | "MCP 管理" | "Agent 管理";
export function CapabilityManagement({ kind }: { kind: Kind }) {
  if (kind === "Skills 管理") return <SkillAdmin />;
  if (kind === "Agent 管理") return <AgentSkillBinding />;
  return <McpManagement />;
}
