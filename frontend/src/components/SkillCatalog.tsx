import { useEffect, useRef, useState } from "react";
import { Search, RefreshCw } from "lucide-react";
import { apiFetch } from "@/auth/api";
import { SkillDialog } from "./SkillDialog";
import { releaseLabel, resourceLabel, skillRequest, type ManagedSkill, type SkillCatalogPage } from "./skillManagement";

const tabs = [{ key: "all", label: "全部" }, { key: "enabled", label: "已启用" }, { key: "disabled", label: "已停用" }, { key: "draft", label: "草稿" }] as const;
const date = (value: string | null) => value ? new Date(value).toLocaleString() : "—";

export function SkillCatalog({ onEdit, onImport }: {
  onEdit: (name: string) => Promise<void>; onImport: () => void;
}) {
  const [state, setState] = useState("all");
  const [query, setQuery] = useState("");
  const [resource, setResource] = useState("all");
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<SkillCatalogPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [target, setTarget] = useState<ManagedSkill | null>(null);
  const sequence = useRef(0);

  async function load(nextPage = page) {
    const request = ++sequence.current;
    setLoading(true);
    try {
      const params = new URLSearchParams({ q: query, state, resource, page: String(nextPage), size: "20" });
      const data = await skillRequest<SkillCatalogPage>(`/api/admin/skills/catalog?${params}`);
      if (request !== sequence.current) return;
      if (nextPage > 1 && !data.items.length) { setPage(nextPage - 1); return; }
      setResult(data);
    } catch (error) { if (request === sequence.current) { setResult(null); setNotice(error instanceof Error ? error.message : "列表加载失败"); } }
    finally { if (request === sequence.current) setLoading(false); }
  }
  useEffect(() => { const timer = window.setTimeout(() => { void load(); }, 200); return () => { window.clearTimeout(timer); sequence.current++; }; }, [state, query, resource, page]);

  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setNotice("");
    try { await action(); } catch (error) { setNotice(error instanceof Error ? error.message : "操作失败"); }
    finally { setBusy(false); }
  }
  async function status() {
    if (!target) return;
    const response = await apiFetch(`/api/admin/skills/${encodeURIComponent(target.name)}/status`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ enabled: target.status !== "0" }),
    });
    if (!response.ok) { const body = await response.json() as { error?: string }; throw new Error(body.error || "启停失败"); }
    setNotice(target.status === "0" ? "已停用，后续新轮次不再加载此 Skill。" : "已启用，后续新轮次可按 Agent 固定版本加载。");
    setTarget(null); await load();
  }
  return <div className="capability skill-catalog">
    <div className="capability-actions">
      <button className="cap-secondary" type="button" disabled={busy} onClick={() => void load()}><RefreshCw size={13}/>刷新</button>
      <button className="cap-primary" type="button" disabled={busy} onClick={onImport}>导入 Skill 包</button>
    </div>
    <div className="cap-tabs" role="tablist" aria-label="状态筛选">{tabs.map(tab => <button type="button" key={tab.key} role="tab" aria-selected={state === tab.key} className={state === tab.key ? "active" : ""} onClick={() => { setState(tab.key); setPage(1); }}>{tab.label} <span>{result?.counts[tab.key] ?? "—"}</span></button>)}</div>
    <div className="admin-filters cap-filters">
      <label className="admin-search"><Search size={16}/><input aria-label="搜索技能" placeholder="搜索名称、标识或负责人" value={query} onChange={event => { setQuery(event.target.value); setPage(1); }}/></label>
      <label className="admin-select"><select aria-label="筛选分类" disabled><option>全部分类（未接入）</option></select></label>
      <label className="admin-select"><select aria-label="资源状态" value={resource} onChange={event => { setResource(event.target.value); setPage(1); }}><option value="all">全部资源状态</option><option value="mapped">映射已齐</option><option value="pending">待接入</option></select></label>
      <span className="cap-count">{loading ? "加载中…" : `共 ${result?.total ?? 0} 个 Skill`}</span>
    </div>
    {notice && <div className="cap-notice" role="status">{notice}</div>}
    {state === "draft" && <p className="cap-hint">正在查看待发布草稿；同一个 Skill 可以同时属于已启用和草稿。草稿资源状态不代表当前生效配置。</p>}
    <div className="admin-card admin-table-card"><div className="admin-table-scroll"><table className="cap-table skill-table"><thead><tr>
      <th>Skill 名称 / 标识</th><th>运行状态</th><th>资源绑定</th><th>近 24h 调用 / 成功率</th><th>负责人 / 更新</th><th>操作</th>
    </tr></thead><tbody>{!loading && result?.items.map(item => <tr key={item.name}>
      <td><button className="cap-name" type="button" disabled={busy} onClick={() => void run(() => onEdit(item.name))}>{item.displayName || item.name}</button>{item.draftVersionId && <span className="skill-draft-badge">有草稿</span>}<small>{item.name} · {state === "draft" ? `草稿 #${item.draftVersionNo ?? "—"}` : item.status === null ? "未发布" : releaseLabel(item)}</small>{state === "draft" && item.currentVersionId && <small>当前生效 {releaseLabel(item)}</small>}</td>
      <td><span className={item.status === "0" ? "admin-green" : "admin-muted"}>{item.status === null ? "未发布" : item.status === "0" ? "已启用" : "已停用"}</span><small>{item.status === null ? "发布后可启用" : item.status === "0" ? "后续新轮次可加载" : "历史与配置保留"}</small></td>
      <td><span className={item.mappedCount < item.dependencyCount ? "cap-warning" : ""}>{resourceLabel(item)}</span><small>{state === "draft" || item.status === null ? "草稿依赖映射" : "当前生效依赖映射"}</small></td>
      <td><span className="admin-muted">未接入</span></td>
      <td>{item.ownerName || (item.ownerId ? `用户 #${item.ownerId}` : "未登记")}<small>{date(item.updatedAt)}</small></td>
      <td><div className="cap-row-actions"><button className={`cap-switch ${item.status === "0" ? "on" : ""}`} type="button" role="switch" aria-checked={item.status === "0"} disabled={busy || item.status === null} aria-label={`${item.status === "0" ? "停用" : "启用"}${item.name}`} onClick={() => { setNotice(""); setTarget(item); }}><span/></button><button className="admin-link" type="button" disabled={busy} onClick={() => void run(() => onEdit(item.name))}>修改</button></div></td>
    </tr>)}</tbody></table>{(loading || !result?.items.length) && <p className="admin-empty">{loading ? "正在加载技能…" : result ? "没有符合条件的 Skill" : "列表未能加载，请刷新重试"}</p>}</div>
      <div className="admin-pagination"><span>第 {result?.page ?? page} 页 · 共 {result?.total ?? 0} 条</span><div><button type="button" disabled={page <= 1 || loading} onClick={() => setPage(page - 1)}>上一页</button><button type="button" disabled={!result || page * result.size >= result.total || loading} onClick={() => setPage(page + 1)}>下一页</button></div></div>
    </div>
    {target && <SkillDialog title={`${target.status === "0" ? "停用" : "启用"}${target.displayName || target.name}？`} busy={busy} onClose={() => { setTarget(null); setNotice(""); }} footer={<><button className="cap-secondary" type="button" disabled={busy} onClick={() => setTarget(null)}>取消</button><button className={target.status === "0" ? "cap-primary skill-danger" : "cap-primary"} type="button" disabled={busy} onClick={() => void run(status)}>{busy ? "处理中…" : target.status === "0" ? "确认停用" : "确认启用"}</button></>}>
      <p>{target.status === "0" ? "停用后，后续新轮次不再加载此 Skill。已开始的轮次不主动中断，已有会议和历史任务保留，不自动取消会议。" : "启用后，后续新轮次可按 Agent 固定版本及用户可见范围加载此 Skill。"}</p>
      <section className="skill-confirm-config"><h3>当前生效配置</h3><dl><dt>能力</dt><dd>{target.displayName || target.name} / {target.name}</dd><dt>发布版本</dt><dd>{releaseLabel(target)}</dd><dt>执行中</dt><dd>未接入</dd><dt>待发布草稿</dt><dd>{target.draftVersionId ? "已保存，保留不变" : "无"}</dd></dl></section>
      <p className="cap-hint">此操作保留用户授权、版本和 Agent 固定引用；具体业务写入仍需独立鉴权。</p>
      {notice && <p role="alert" className="cap-error">{notice}</p>}
    </SkillDialog>}

  </div>;
}
