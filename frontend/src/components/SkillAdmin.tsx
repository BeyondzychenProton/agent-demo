import { useEffect, useState } from "react";
import { ArrowLeft, Plus, Search, X } from "lucide-react";
import { apiFetch } from "@/auth/api";

type SkillFile = { path: string; content: string };
type SkillItem = {
  name: string;
  description: string;
  status: string;
  visibleScope: string;
  ownerDeptId: number | null;
  ownerDeptName: string | null;
  resourceCount: number;
  bound: boolean;
  updatedAt: string | null;
};
type SkillPage = { items: SkillItem[]; total: number; page: number; size: number };
type SkillDetail = SkillItem & { content: string; files: SkillFile[] };
type Department = { id: string; name: string; status: string };
type Draft = {
  name: string;
  description: string;
  content: string;
  visibleScope: string;
  ownerDeptId: string;
  status: string;
  files: SkillFile[];
};

const emptyDraft = (): Draft => ({
  name: "",
  description: "",
  content: "",
  visibleScope: "0",
  ownerDeptId: "",
  status: "0",
  files: [],
});

function quote(value: string) {
  return `"${value.replace(/\r\n|\r|\n/g, " ").replace(/\\/g, "\\\\").replace(/"/g, "\\\"")}"`;
}

function composeSkill(draft: Draft) {
  return `---\nname: ${quote(draft.name)}\ndescription: ${quote(draft.description)}\n---\n\n${draft.content}`;
}

async function readError(response: Response) {
  try {
    const body = await response.json() as { error?: string };
    return body.error || `请求失败（${response.status}）`;
  } catch {
    return `请求失败（${response.status}）`;
  }
}

function scopeLabel(scope: string) {
  if (scope === "1") return "本部门";
  if (scope === "2") return "仅本人";
  return "公开";
}

function when(value: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export function SkillAdmin() {
  const [query, setQuery] = useState("");
  const [state, setState] = useState("all");
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<SkillPage | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [creating, setCreating] = useState(false);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(false);

  async function load(nextPage = page, nextQuery = query, nextState = state) {
    setLoading(true);
    setNotice("");
    const params = new URLSearchParams({ q: nextQuery, state: nextState, page: String(nextPage), size: "20" });
    try {
      const response = await apiFetch(`/api/admin/skills?${params}`);
      if (!response.ok) throw new Error(await readError(response));
      setResult(await response.json() as SkillPage);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "技能列表加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(1, "", "all"); }, []);

  useEffect(() => {
    void apiFetch("/api/admin/directory")
      .then(async response => response.ok ? response.json() as Promise<{ departments: Department[] }> : { departments: [] })
      .then(directory => setDepartments(directory.departments.filter(item => item.status === "0")))
      .catch(() => setDepartments([]));
  }, []);

  async function open(name: string) {
    setNotice("");
    const response = await apiFetch(`/api/admin/skills/${encodeURIComponent(name)}`);
    if (!response.ok) { setNotice(await readError(response)); return; }
    const detail = await response.json() as SkillDetail;
    setCreating(false);
    setDraft({
      name: detail.name,
      description: detail.description,
      content: detail.content,
      visibleScope: detail.visibleScope || "0",
      ownerDeptId: detail.ownerDeptId == null ? "" : String(detail.ownerDeptId),
      status: detail.status || "0",
      files: detail.files || [],
    });
  }

  async function save() {
    if (!draft) return;
    if (!draft.description.trim()) { setNotice("请填写用途说明"); return; }
    const body = {
      name: draft.name.trim(),
      description: draft.description,
      content: draft.content,
      visibleScope: draft.visibleScope,
      ownerDeptId: draft.visibleScope === "1" && draft.ownerDeptId ? Number(draft.ownerDeptId) : null,
      status: draft.status,
      files: draft.files,
    };
    const response = await apiFetch(creating ? "/api/admin/skills" : `/api/admin/skills/${encodeURIComponent(draft.name)}`, {
      method: creating ? "POST" : "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!response.ok) { setNotice(await readError(response)); return; }
    const saved = await response.json() as SkillDetail;
    setCreating(false);
    setDraft({ ...draft, name: saved.name, content: saved.content, files: saved.files || draft.files });
    await load();
    setNotice("已保存");
  }

  async function toggle(item: SkillItem) {
    const response = await apiFetch(`/api/admin/skills/${encodeURIComponent(item.name)}/status`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled: item.status !== "0" }),
    });
    if (!response.ok) { setNotice(await readError(response)); return; }
    await load();
  }

  async function remove() {
    if (!draft || creating) return;
    if (!window.confirm(`删除技能 ${draft.name}？已绑定的技能需要先解绑。`)) return;
    const response = await apiFetch(`/api/admin/skills/${encodeURIComponent(draft.name)}`, { method: "DELETE" });
    if (!response.ok) { setNotice(await readError(response)); return; }
    setDraft(null);
    await load();
  }

  async function importFile(file: File) {
    const body = new FormData();
    body.set("file", file);
    const response = await apiFetch("/api/admin/skills/import", { method: "POST", body });
    if (!response.ok) { setNotice(await readError(response)); return; }
    await load(1, query, state);
    setNotice(`已导入 ${file.name}`);
  }

  function updateFile(index: number, patch: Partial<SkillFile>) {
    setDraft(current => current ? {
      ...current,
      files: current.files.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item),
    } : current);
  }

  function removeFile(index: number) {
    setDraft(current => current ? {
      ...current,
      files: current.files.filter((_item, itemIndex) => itemIndex !== index),
    } : current);
  }

  const items = result?.items ?? [];
  return <div className="capability">
    {!draft ? <>
      <div className="capability-actions">
        <label className="cap-secondary">导入<input type="file" accept=".md,.zip,text/markdown,application/zip" hidden onChange={event => {
          const file = event.target.files?.[0];
          event.target.value = "";
          if (file) void importFile(file);
        }}/></label>
        <button className="cap-primary" type="button" onClick={() => { setDraft(emptyDraft()); setCreating(true); setNotice(""); }}><Plus size={15}/>新增 Skill</button>
      </div>
      <div className="admin-filters cap-filters">
        <label className="admin-search"><Search size={16}/><input aria-label="搜索技能" value={query} placeholder="搜索名称或用途说明" onChange={event => setQuery(event.target.value)} onKeyDown={event => { if (event.key === "Enter") { setPage(1); void load(1, query, state); } }}/></label>
        <label className="admin-select"><select aria-label="筛选状态" value={state} onChange={event => { setState(event.target.value); setPage(1); void load(1, query, event.target.value); }}><option value="all">全部</option><option value="enabled">已启用</option><option value="disabled">已停用</option></select></label>
        <button className="admin-reset" type="button" onClick={() => { setPage(1); void load(1, query, state); }}>查询</button>
        <span className="cap-count">{loading ? "加载中" : `共 ${result?.total ?? 0} 个 Skill`}</span>
      </div>
      {notice && <div className="cap-notice" role="status">{notice}<button aria-label="关闭提示" type="button" onClick={() => setNotice("")}><X size={14}/></button></div>}
      <div className="admin-card admin-table-card"><div className="admin-table-scroll"><table className="cap-table"><thead><tr><th>名称</th><th>用途说明</th><th>状态</th><th>可见范围</th><th>绑定</th><th>更新时间</th><th>操作</th></tr></thead><tbody>
        {items.map(item => <tr key={item.name}>
          <td><button className="cap-name" type="button" onClick={() => void open(item.name)}>{item.name}</button></td>
          <td>{item.description}</td>
          <td><span className={item.status === "0" ? "admin-green" : "admin-muted"}>{item.status === "0" ? "已启用" : "已停用"}</span></td>
          <td>{scopeLabel(item.visibleScope)}{item.visibleScope === "1" && item.ownerDeptName ? ` · ${item.ownerDeptName}` : ""}</td>
          <td>{item.bound ? "已绑定" : "未绑定"}</td>
          <td>{when(item.updatedAt)}</td>
          <td><div className="cap-row-actions"><button className={`cap-switch ${item.status === "0" ? "on" : ""}`} type="button" role="switch" aria-checked={item.status === "0"} aria-label={`${item.status === "0" ? "停用" : "启用"}${item.name}`} onClick={() => void toggle(item)}><span/></button><button className="admin-link" type="button" onClick={() => void open(item.name)}>编辑</button></div></td>
        </tr>)}
      </tbody></table>{!items.length && <p className="admin-empty">{loading ? "正在加载技能…" : "没有技能"}</p>}</div>
      <div className="admin-pagination"><span>第 {result?.page ?? 1} 页</span><div><button type="button" disabled={page <= 1} onClick={() => { const next = page - 1; setPage(next); void load(next); }}>上一页</button><button type="button" disabled={!result || page * result.size >= result.total} onClick={() => { const next = page + 1; setPage(next); void load(next); }}>下一页</button></div></div></div>
    </> : <>
      <div className="cap-detail-heading"><div><button className="cap-back" type="button" onClick={() => { setDraft(null); void load(); }}><ArrowLeft size={15}/> 返回列表</button><h2>{creating ? "新增 Skill" : draft.name}</h2><p>技能名创建后不能修改。原文预览是将要写入的 SKILL.md。</p></div><div className="cap-detail-actions">{!creating && <button className="cap-secondary" type="button" onClick={() => void remove()}>删除</button>}<button className="cap-primary" type="button" onClick={() => void save()}>保存</button></div></div>
      {notice && <div className="cap-notice" role="status">{notice}<button aria-label="关闭提示" type="button" onClick={() => setNotice("")}><X size={14}/></button></div>}
      <div className="cap-detail-grid">
        <section className="admin-card cap-panel">
          <div className="cap-form cap-form-grid">
            <label>技能名 *<input aria-label="技能名" value={draft.name} disabled={!creating} onChange={event => setDraft({ ...draft, name: event.target.value })} placeholder="例如 meeting-booking"/></label>
            <label>状态<select aria-label="状态" value={draft.status} onChange={event => setDraft({ ...draft, status: event.target.value })}><option value="0">启用</option><option value="1">停用</option></select></label>
            <label className="cap-full">用途说明 *<textarea aria-label="用途说明" value={draft.description} onChange={event => setDraft({ ...draft, description: event.target.value })}/></label>
            <label>可见范围<select aria-label="可见范围" value={draft.visibleScope} onChange={event => setDraft({ ...draft, visibleScope: event.target.value })}><option value="0">公开</option><option value="1">本部门</option><option value="2">仅本人</option></select></label>
            <label>部门<select aria-label="部门" value={draft.ownerDeptId} disabled={draft.visibleScope !== "1"} onChange={event => setDraft({ ...draft, ownerDeptId: event.target.value })}><option value="">{draft.visibleScope === "1" ? "不选则使用我的部门" : "仅本部门时需要"}</option>{departments.map(department => <option key={department.id} value={department.id}>{department.name}</option>)}</select></label>
            <label className="cap-full">正文<textarea aria-label="技能正文" value={draft.content} onChange={event => setDraft({ ...draft, content: event.target.value })} placeholder="不要填写 frontmatter"/></label>
          </div>
          <h3>参考资料</h3>
          {draft.files.map((file, index) => (
            <div className="cap-form" key={index}>
              <label>路径<input aria-label={`参考资料路径 ${index + 1}`} value={file.path} placeholder="references/faq.md" onChange={event => updateFile(index, { path: event.target.value })}/></label>
              <label>内容<textarea aria-label={`参考资料内容 ${index + 1}`} value={file.content} onChange={event => updateFile(index, { content: event.target.value })}/></label>
              <button className="admin-link" type="button" onClick={() => removeFile(index)}>移除</button>
            </div>
          ))}
          <button className="cap-secondary" type="button" onClick={() => setDraft({ ...draft, files: [...draft.files, { path: "", content: "" }] })}>添加参考资料</button>
        </section>
        <aside className="cap-side"><div className="admin-card cap-panel"><h3>原文预览</h3><pre className="cap-source">{composeSkill(draft)}</pre></div></aside>
      </div>
    </>}
  </div>;
}
