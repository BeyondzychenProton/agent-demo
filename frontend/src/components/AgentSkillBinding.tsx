import { useEffect, useState } from "react";
import { X } from "lucide-react";
import { apiFetch } from "@/auth/api";

type Option = { name: string; description: string };
type Bound = { name: string; description: string; status: string; visibleScope: string };

const AGENT = "session-chat";

async function readError(response: Response) {
  try {
    const body = await response.json() as { error?: string };
    return body.error || `请求失败（${response.status}）`;
  } catch {
    return `请求失败（${response.status}）`;
  }
}

export function AgentSkillBinding() {
  const [options, setOptions] = useState<Option[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [held, setHeld] = useState<Bound[]>([]);
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(true);

  async function load() {
    setLoading(true);
    setNotice("");
    try {
      const [optionResponse, agentResponse] = await Promise.all([
        apiFetch("/api/admin/skills/options"),
        apiFetch(`/api/admin/agents/${AGENT}`),
      ]);
      if (!optionResponse.ok) throw new Error(await readError(optionResponse));
      if (!agentResponse.ok) throw new Error(await readError(agentResponse));
      const available = await optionResponse.json() as Option[];
      const agent = await agentResponse.json() as { skills: Bound[] };
      const names = new Set(available.map(item => item.name));
      setOptions(available);
      setSelected(agent.skills.filter(item => names.has(item.name)).map(item => item.name));
      setHeld(agent.skills.filter(item => !names.has(item.name)));
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "绑定加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  async function save() {
    const response = await apiFetch(`/api/admin/agents/${AGENT}/skills`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ names: selected }),
    });
    if (!response.ok) { setNotice(await readError(response)); return; }
    setNotice("绑定已保存");
    await load();
  }

  function toggle(name: string) {
    setSelected(current => current.includes(name) ? current.filter(item => item !== name) : [...current, name]);
  }

  return <div className="capability">
    <div className="capability-actions"><button className="cap-primary" type="button" onClick={() => void save()} disabled={loading}>保存绑定</button></div>
    {notice && <div className="cap-notice" role="status">{notice}<button aria-label="关闭提示" type="button" onClick={() => setNotice("")}><X size={14}/></button></div>}
    <section className="admin-card cap-panel">
      <h3>海卓大脑办公助手</h3>
      <p>标识 session-chat。勾选来自当前账号可见且启用的技能，保存后覆盖这一条智能体的绑定。</p>
      {loading && <p>正在加载绑定…</p>}
      {!loading && !options.length && <p>没有可绑定的技能。</p>}
      {options.map(skill => <label className="cap-choice" key={skill.name}><div><strong>{skill.name}</strong><small>{skill.description}</small></div><input type="checkbox" aria-label={skill.name} checked={selected.includes(skill.name)} onChange={() => toggle(skill.name)}/></label>)}
      {held.length > 0 && <div className="cap-hint">这些技能已经绑定，但当前不能再勾选（已停用或对你不可见）。保存会解除它们：{held.map(item => item.name).join("、")}</div>}
    </section>
  </div>;
}
