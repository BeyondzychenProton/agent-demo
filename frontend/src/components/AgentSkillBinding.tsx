import { useEffect, useState } from "react";
import { apiFetch } from "@/auth/api";
import { releaseLabel, skillRequest, type BoundSkill } from "./skillManagement";

type Version = { id: string; platformVersion: string | null; versionNo: string };
type Option = { name: string; description: string; currentVersionId: string | null; versions: Version[] };
const AGENT = "session-chat";

export function AgentSkillBinding() {
  const [options, setOptions] = useState<Option[]>([]);
  const [held, setHeld] = useState<BoundSkill[]>([]);
  const [selected, setSelected] = useState<Record<string, string | null>>({});
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  async function load() {
    setLoading(true);
    try {
      const [available, agent] = await Promise.all([
        skillRequest<Option[]>("/api/admin/skills/options"),
        skillRequest<{ skills: BoundSkill[] }>(`/api/admin/agents/${AGENT}`),
      ]);
      setOptions(available); setHeld(agent.skills);
      setSelected(Object.fromEntries(agent.skills.map(item => [item.name, item.versionId])));
      setReady(true);
      return true;
    } catch (error) { setReady(false); setNotice(error instanceof Error ? error.message : "绑定加载失败"); return false; }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, []);
  async function save() {
    setBusy(true); setNotice("");
    try {
      const response = await apiFetch(`/api/admin/agents/${AGENT}/skills`, {
        method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ bindings: Object.entries(selected).map(([name, versionId]) => ({ name, versionId })) }),
      });
      if (!response.ok) { const body = await response.json() as { error?: string }; throw new Error(body.error || "绑定保存失败"); }
      if (await load()) setNotice("固定版本已保存，后续新轮次使用所选版本。已开始的轮次保持原内容。");
    } catch (error) { setNotice(error instanceof Error ? error.message : "绑定保存失败"); }
    finally { setBusy(false); }
  }
  const rows = [...options, ...held.filter(item => !options.some(option => option.name === item.name))
    .map(item => ({ name: item.name, description: item.description, currentVersionId: null, versions: [] }))];
  return <div className="capability">
    <div className="capability-actions"><button className="cap-secondary" type="button" disabled={loading || busy} onClick={() => void load()}>刷新</button><button className="cap-primary" type="button" onClick={() => void save()} disabled={!ready || loading || busy}>{busy ? "保存中…" : "保存固定版本"}</button></div>
    {notice && <div className="cap-notice" role="status">{notice}</div>}
    <section className="admin-card cap-panel"><h3>海卓大脑办公助手</h3>
      <p>每个 Skill 固定一个已发布版本。Skill 发布新版本不会自动升级此 Agent，需在此明确选择并保存。</p>
      <p className="cap-hint">已停用或当前不可选的绑定默认保留。全局停用仍会阻止后续新轮次加载；取消勾选才会解除引用。</p>
      {loading && <p>正在加载绑定…</p>}
      {!loading && !rows.length && <p>没有可绑定的技能。</p>}
      {!loading && rows.map(skill => {
        const current = held.find(item => item.name === skill.name);
        const available = options.some(item => item.name === skill.name);
        const checked = Object.prototype.hasOwnProperty.call(selected, skill.name);
        const versions = [...skill.versions];
        if (current?.versionId && !versions.some(item => item.id === current.versionId)) versions.push({ id: current.versionId, platformVersion: current.platformVersion, versionNo: current.versionNo || "" });
        return <div className="cap-choice skill-binding" key={skill.name}>
          <label><input type="checkbox" aria-label={skill.name} checked={checked} disabled={busy} onChange={event => setSelected(previous => {
            const next = { ...previous };
            if (event.target.checked) next[skill.name] = current?.versionId || skill.currentVersionId;
            else delete next[skill.name];
            return next;
          })}/><div><strong>{skill.name}</strong><small>{skill.description}</small><small>{!available ? "当前不可选，保留已有引用" : `当前 Agent 引用：${current ? releaseLabel(current) : "未绑定"}`}</small></div></label>
          {checked && <label className="skill-version-picker">固定版本<select aria-label={`${skill.name} 固定版本`} disabled={!available || busy} value={selected[skill.name] || ""} onChange={event => setSelected(previous => ({ ...previous, [skill.name]: event.target.value || null }))}>
            {!selected[skill.name] && <option value="">当前运行基线（保存时固定）</option>}
            {versions.map(version => <option key={version.id} value={version.id}>{releaseLabel(version)}{skill.currentVersionId === version.id ? "（当前生效）" : ""}</option>)}
          </select></label>}
        </div>;
      })}
    </section>
  </div>;
}
