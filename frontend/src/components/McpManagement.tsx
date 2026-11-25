import { useEffect, useRef, useState } from "react";
import { Search, Plus, X, RefreshCw } from "lucide-react";
import { SkillDialog } from "./SkillDialog";
import { healthLabel, mcpRequest, mcpTime, mcpWrite, toolLoadLabel, operationLines,
  type McpList, type McpOperation, type McpPage, type McpService, type McpTool } from "./mcpApi";
import "./mcpManagement.css";

type Form = { displayName: string; description: string; ownerName: string };
type Toggle = { service: McpService; tool?: McpTool };
const formOf = (s: McpService): Form => ({ displayName: s.displayName, description: s.description || "", ownerName: s.ownerName || "" });
const errorText = (e: unknown) => e instanceof Error ? e.message : "操作失败，请重试";
const actionNames: Record<string, string> = { METADATA_UPDATED: "修改服务信息", SERVICE_STATUS_CHANGED: "调整服务启停", TOOL_STATUS_CHANGED: "调整工具开放", CONNECTION_VERIFIED: "验证连接", CATALOG_SYNCED: "启动发现" };
const resultNames: Record<string, string> = { SUCCESS: "成功", FAILED: "失败", SUPERSEDED: "已被新检测取代" };
export function McpManagement() {
  const [list, setList] = useState<McpList>({ items: [], total: 0, page: 1, size: 20, counts: { all: 0, enabled: 0, disabled: 0 } });
  const [query, setQuery] = useState(""), [state, setState] = useState("all"), [health, setHealth] = useState(""), [transport, setTransport] = useState(""), [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false), [busy, setBusy] = useState(false), [notice, setNotice] = useState(""), [loadError, setLoadError] = useState("");
  const [selected, setSelected] = useState<McpService | null>(null), [form, setForm] = useState<Form | null>(null), [original, setOriginal] = useState("");
  const [tab, setTab] = useState("detail"), [refresh, setRefresh] = useState(0), [formVersion, setFormVersion] = useState(0);
  const [toolPage, setToolPage] = useState(1), [toolQuery, setToolQuery] = useState(""), [toolState, setToolState] = useState("all");
  const [tools, setTools] = useState<McpPage<McpTool>>({ items: [], total: 0, page: 1, size: 20 }), [toolsError, setToolsError] = useState("");
  const [toolLoading, setToolLoading] = useState(false), [tool, setTool] = useState<McpTool | null>(null);
  const [history, setHistory] = useState<McpPage<McpOperation>>({ items: [], total: 0, page: 1, size: 20 }), [historyPage, setHistoryPage] = useState(1), [historyError, setHistoryError] = useState("");
  const [toggle, setToggle] = useState<Toggle | null>(null), [leave, setLeave] = useState(false);
  const pending = useRef<(() => void) | null>(null), selectedId = selected?.id, drawer = useRef<HTMLElement>(null);
  const dirty = form !== null && JSON.stringify(form) !== original;
  useEffect(() => {
    if (!selectedId) return;
    const previous = document.activeElement;
    drawer.current?.focus();
    return () => { if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, [selectedId]);

  useEffect(() => {
    let active = true; setLoading(true); setLoadError("");
    const params = new URLSearchParams({ q: query, state, health, transport, page: String(page), size: "20" });
    mcpRequest<McpList>("?" + params).then(v => { if (active) setList(v); }).catch(e => { if (active) setLoadError(errorText(e)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [query, state, health, transport, page, refresh]);
  useEffect(() => {
    if (!selectedId) return;
    let active = true; setToolsError(""); setToolLoading(true);
    const params = new URLSearchParams({ q: toolQuery, state: toolState, page: String(toolPage), size: "20" });
    mcpRequest<McpPage<McpTool>>("/" + selectedId + "/tools?" + params).then(v => { if (active) setTools(v); }).catch(e => { if (active) setToolsError(errorText(e)); }).finally(() => { if (active) setToolLoading(false); });
    return () => { active = false; };
  }, [selectedId, toolPage, toolQuery, toolState, refresh]);
  useEffect(() => {
    if (!selectedId || tab !== "history") return;
    let active = true; setHistoryError("");
    mcpRequest<McpPage<McpOperation>>("/" + selectedId + "/operations?page=" + historyPage).then(v => { if (active) setHistory(v); }).catch(e => { if (active) setHistoryError(errorText(e)); });
    return () => { active = false; };
  }, [selectedId, tab, historyPage, refresh]);
  useEffect(() => {
    const navigate = (event: Event) => {
      if (!dirty && !busy) return;
      event.preventDefault();
      if (busy) { setNotice("操作处理中，请完成后再离开"); return; }
      pending.current = (event as CustomEvent<{ resume: () => void }>).detail.resume; setLeave(true);
    };
    const unload = (e: BeforeUnloadEvent) => { if (dirty || busy) { e.preventDefault(); e.returnValue = ""; } };
    window.addEventListener("admin-before-navigate", navigate); window.addEventListener("beforeunload", unload);
    return () => { window.removeEventListener("admin-before-navigate", navigate); window.removeEventListener("beforeunload", unload); };
  }, [dirty, busy]);
  async function run(action: () => Promise<void>) {
    setBusy(true); setNotice("");
    try { await action(); } catch (e) { setNotice(errorText(e)); } finally { setBusy(false); }
  }
  async function open(id: string) {
    await run(async () => {
      const v = await mcpRequest<McpService>("/" + id); setSelected(v);
      const f = formOf(v); setForm(f); setOriginal(JSON.stringify(f)); setFormVersion(v.controlVersion); setTab("detail");
      setToolPage(1); setToolQuery(""); setToolState("all"); setHistoryPage(1);
    });
  }
  function close() {
    if (busy) return;
    if (dirty) { pending.current = () => setSelected(null); setLeave(true); return; }
    setSelected(null); setForm(null); setTool(null);
  }
  async function reloadSelected(id: string) {
    const v = await mcpRequest<McpService>("/" + id);
    setSelected(current => current?.id === id ? v : current);
    if (!dirty && selected?.id === id) {
      const f = formOf(v); setForm(f); setOriginal(JSON.stringify(f)); setFormVersion(v.controlVersion);
    }
    setRefresh(n => n + 1);
  }
  async function save() {
    if (!selected || !form) return;
    await run(async () => {
      const v = await mcpWrite<McpService>("/" + selected.id + "/metadata", { ...form, expectedVersion: formVersion }, "PUT");
      setSelected(v); const f = formOf(v); setForm(f); setOriginal(JSON.stringify(f)); setFormVersion(v.controlVersion);
      setRefresh(n => n + 1); setNotice("服务信息已保存");
    });
  }
  async function verify() {
    if (!selected) return;
    await run(async () => {
      const v = await mcpWrite<{ success: boolean; message: string }>("/" + selected.id + "/verify");
      await reloadSelected(selected.id); setNotice(v.message);
    });
  }
  async function applyToggle() {
    if (!toggle) return;
    const { service, tool: t } = toggle;
    await run(async () => {
      await mcpWrite("/" + service.id + (t ? "/tools/" + t.id : "") + "/status", { enabled: !(t || service).enabled, expectedVersion: (t || service).controlVersion });
      setToggle(null);
      if (selected?.id === service.id) await reloadSelected(service.id); else setRefresh(n => n + 1);
      setNotice("状态已保存，后续调用按最新开关检查");
    });
  }
  const notices = notice && <div className="cap-notice" role="status">{notice}</div>;
  return <div className="capability mcp-management">
    <div className="capability-actions"><button className="cap-secondary" disabled={loading || busy} onClick={() => setRefresh(n => n + 1)}><RefreshCw size={15}/>刷新列表</button><button className="cap-primary" disabled title="统一接入流程建设中，当前由代码配置 MCP"><Plus size={15}/>新增 MCP 服务</button></div>
    <p className="mcp-hint">当前服务由代码配置接入。管理开关独立于连接健康。</p>{notices}
    <div className="cap-tabs" role="tablist" aria-label="MCP 状态筛选">{[["all", "全部", list.counts.all], ["enabled", "已启用", list.counts.enabled], ["disabled", "已停用", list.counts.disabled]].map(([v, label, n]) => <button key={v} role="tab" aria-selected={state === v} className={state === v ? "active" : ""} onClick={() => { setState(String(v)); setPage(1); }}>{label} <span>{n}</span></button>)}</div>
    <div className="admin-filters"><label className="admin-search"><Search size={16}/><input aria-label="搜索 MCP 服务" placeholder="搜索服务名称、标识或说明" value={query} onChange={e => { setQuery(e.target.value); setPage(1); }}/></label>
      <select aria-label="筛选传输方式" value={transport} onChange={e => { setTransport(e.target.value); setPage(1); }}><option value="">全部传输方式</option><option>Streamable HTTP</option></select>
      <select aria-label="筛选连接健康" value={health} onChange={e => { setHealth(e.target.value); setPage(1); }}><option value="">全部连接健康</option>{["HEALTHY", "FAILED", "UNKNOWN", "CHECKING", "INTERRUPTED"].map(v => <option value={v} key={v}>{healthLabel(v)}</option>)}</select></div>
    {loadError ? <p className="mcp-error" role="alert">{loadError}<button className="admin-link" onClick={() => setRefresh(n => n + 1)}>重试</button></p> : <div className="admin-card mcp-table-wrap"><table className="admin-table"><thead><tr><th>MCP 服务／标识</th><th>传输方式</th><th>运行与连接</th><th>工具</th><th>启停</th><th>操作</th></tr></thead><tbody>
      {list.items.map(s => <tr key={s.id}><td><button className="admin-link" disabled={busy} onClick={() => open(s.id)}>{s.displayName}</button><small>{s.providerKey}</small></td><td>{s.transport || "—"}</td><td><span className={s.enabled ? "admin-green" : ""}>{s.enabled ? "已启用" : "已停用"}</span><small>{s.configured ? healthLabel(s.health) : "配置已移除"}{s.lastLatencyMs !== null ? " · " + s.lastLatencyMs + " ms" : ""}</small>{s.historicalSnapshot && <small>历史快照</small>}</td><td>{s.discoveredCount} 已发现<small>{s.openCount} 已开放</small></td><td><Switch checked={s.enabled} label={(s.enabled ? "停用" : "启用") + s.displayName} disabled={busy || !s.configured} onClick={() => setToggle({ service: s })}/></td><td><button className="admin-link" disabled={busy} onClick={() => open(s.id)}>详情</button></td></tr>)}
      {!list.items.length && <tr><td colSpan={6} className="mcp-empty">{loading ? "正在加载服务…" : "没有符合条件的 MCP 服务"}</td></tr>}
    </tbody></table><Pager total={list.total} page={page} busy={loading || busy} onChange={setPage}/></div>}
    {selected && <div className="mcp-drawer-overlay" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}><section ref={drawer} tabIndex={-1} className="mcp-drawer" role="dialog" aria-modal="true" aria-label={selected.displayName + "服务详情"} onKeyDown={e => {
      if (tool || toggle || leave) return;
      if (e.key === "Escape") { e.stopPropagation(); close(); }
      if (e.key !== "Tab") return;
      const elements = drawer.current?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled)');
      if (!elements?.length) { e.preventDefault(); return; }
      const first = elements[0], last = elements[elements.length - 1];
      if (e.shiftKey && (document.activeElement === first || document.activeElement === drawer.current)) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }}>
      <header><div><h2>{selected.displayName}</h2><p>{selected.providerKey}</p></div><button className="mcp-icon" disabled={busy} aria-label="关闭服务详情" onClick={close}><X size={19}/></button></header>
      <div className="mcp-drawer-body">{notices}<div className="cap-tabs"><button className={tab === "detail" ? "active" : ""} onClick={() => setTab("detail")}>服务详情</button><button className={tab === "history" ? "active" : ""} onClick={() => setTab("history")}>变更记录</button></div>
        {tab === "detail" ? <>
          <div className={"mcp-banner " + (selected.health === "HEALTHY" ? "healthy" : "")}>{selected.enabled ? "已启用" : "已停用"} · {selected.configured ? healthLabel(selected.health) : "配置已移除"}{selected.lastLatencyMs !== null && " · " + selected.lastLatencyMs + " ms"}{selected.lastErrorMessage && <p>{selected.lastErrorMessage}</p>}{selected.historicalSnapshot && <p>当前展示上次成功发现的历史快照。</p>}<small>最近检测：{mcpTime(selected.lastCheckedAt)}　上次成功：{mcpTime(selected.lastSuccessAt)}</small></div>
          <div className="mcp-counts"><div className="admin-card"><small>已发现工具</small><strong>{selected.discoveredCount}</strong></div><div className="admin-card"><small>已开放工具</small><strong>{selected.openCount}</strong></div></div>
          <section className="admin-card cap-panel"><h3>服务信息</h3><dl className="mcp-info"><dt>唯一标识</dt><dd>{selected.providerKey}</dd><dt>传输方式</dt><dd>{selected.transport || "—"}</dd><dt>服务端点</dt><dd>{selected.endpoint || "配置已移除"}</dd><dt>认证方式</dt><dd>{selected.authType || "—"}</dd><dt>负责人／更新</dt><dd>{selected.ownerName || "未填写"} · {mcpTime(selected.updatedAt)}</dd></dl>
            {form && <div className="mcp-form"><label>名称 *<input maxLength={128} value={form.displayName} disabled={busy} onChange={e => setForm({ ...form, displayName: e.target.value })}/></label><label>说明<textarea maxLength={1000} value={form.description} disabled={busy} onChange={e => setForm({ ...form, description: e.target.value })}/></label><label>负责人<input maxLength={128} value={form.ownerName} disabled={busy} onChange={e => setForm({ ...form, ownerName: e.target.value })}/></label><div className="mcp-form-actions"><button className="cap-secondary" disabled={busy} onClick={() => {
              if (dirty) { pending.current = () => { void open(selected.id); }; setLeave(true); } else void open(selected.id);
            }}>重新加载信息</button><button className="cap-primary" disabled={busy || !dirty || !form.displayName.trim()} onClick={save}>保存信息</button></div></div>}</section>
          <section className="admin-card cap-panel"><h3>工具清单</h3><p className="mcp-hint">开放不替代业务鉴权和写操作审批。新增及定义变化需重启后加载。</p>
            <div className="admin-filters"><input aria-label="搜索工具" placeholder="搜索工具名称或说明" value={toolQuery} onChange={e => { setToolQuery(e.target.value); setToolPage(1); }}/><select aria-label="筛选工具开放状态" value={toolState} onChange={e => { setToolState(e.target.value); setToolPage(1); }}><option value="all">全部工具</option><option value="open">已开放</option><option value="closed">已关闭</option></select></div>
            {toolsError ? <p className="mcp-error" role="alert">{toolsError}</p> : <><div className="mcp-table-wrap"><table className="admin-table"><thead><tr><th>工具／说明</th><th>操作性质</th><th>加载状态</th><th>开放</th></tr></thead><tbody>{tools.items.map(t => <tr key={t.id}><td><button className="admin-link" disabled={busy} onClick={() => run(async () => setTool(await mcpRequest<McpTool>("/" + selected.id + "/tools/" + t.id)))}>{t.toolKey}</button><small>{t.description || "无说明"}</small></td><td>{t.write ? "写入 · 需审批" : "只读"}</td><td>{toolLoadLabel(t)}</td><td><Switch checked={t.enabled} label={(t.enabled ? "关闭" : "开放") + t.toolKey} disabled={busy || !selected.configured || !t.present} onClick={() => setToggle({ service: selected, tool: t })}/></td></tr>)}{!tools.items.length && <tr><td colSpan={4} className="mcp-empty">{toolLoading ? "正在加载工具…" : "没有符合条件的工具"}</td></tr>}</tbody></table></div><Pager total={tools.total} page={toolPage} busy={busy || toolLoading} onChange={setToolPage}/></>}
            <p className="mcp-hint">加载状态仅代表当前响应实例；多实例部署需完成全部实例重启。</p></section>
        </> : <section className="admin-card cap-panel"><h3>管理变更记录</h3>{historyError ? <p role="alert" className="mcp-error">{historyError}</p> : <>{history.items.map(op => <article className="mcp-operation" key={op.id}><strong>{actionNames[op.action] || op.action}</strong><small>{mcpTime(op.createdAt)} · {op.actorName || "系统"} · {resultNames[op.result] || op.result}</small>{operationLines(op).map((line, i) => <p key={i}>{line}</p>)}</article>)}{!history.items.length && <p>暂无管理变更记录</p>}<Pager total={history.total} page={historyPage} busy={busy} onChange={setHistoryPage}/></>}</section>}
      </div><footer><button className="cap-secondary" disabled={busy} onClick={close}>关闭</button><button className="cap-secondary" disabled={busy || !selected.configured} onClick={() => setToggle({ service: selected })}>{selected.enabled ? "停用服务" : "启用服务"}</button><button className="cap-primary" disabled={busy || !selected.configured} onClick={verify}>{busy ? "处理中…" : "重新验证连接"}</button></footer>
    </section></div>}
    {tool && <SkillDialog title="工具详情" busy={busy} onClose={() => setTool(null)} footer={<button className="cap-secondary" disabled={busy} onClick={() => setTool(null)}>关闭</button>}><h3>{tool.toolKey}</h3><p>{tool.description || "服务未提供说明"}</p><p>{tool.write ? "写入操作，需用户审批" : "只读操作"} · {tool.enabled ? "已开放" : "已关闭"} · {toolLoadLabel(tool)}</p>{!tool.serviceEnabled && <p className="mcp-error">服务已停用，当前不可调用。</p>}<h3>输入参数声明</h3><pre className="mcp-schema">{JSON.stringify(tool.inputSchema, null, 2)}</pre><h3>输出结构</h3><p>未提供输出结构</p></SkillDialog>}
    {toggle && <SkillDialog title={toggle.tool ? "调整工具开放" : "调整服务启停"} busy={busy} onClose={() => setToggle(null)} footer={<><button className="cap-secondary" disabled={busy} onClick={() => setToggle(null)}>取消</button><button className="cap-primary" disabled={busy} onClick={applyToggle}>确认{(toggle.tool || toggle.service).enabled ? "停用" : "启用"}</button></>}><p>确认{(toggle.tool || toggle.service).enabled ? "停用" : "启用"}“{toggle.tool?.toolKey || toggle.service.displayName}”？</p><p>停用后，后续尚未进入执行的调用将被拦截；等待审批的调用即使获批也不能绕过。已提交的请求继续处理。</p>{!toggle.service.enabled && toggle.tool && <p>服务当前停用，工具开放后仍不可调用。</p>}</SkillDialog>}
    {leave && <SkillDialog title="尚有未保存的修改" onClose={() => setLeave(false)} footer={<><button className="cap-secondary" onClick={() => setLeave(false)}>继续编辑</button><button className="cap-primary" onClick={() => { setLeave(false); setForm(null); pending.current?.(); pending.current = null; }}>放弃修改</button></>}>离开后未保存的服务信息将丢失。</SkillDialog>}
  </div>;
}
function Switch({ checked, label, disabled, onClick }: { checked: boolean; label: string; disabled: boolean; onClick: () => void }) {
  return <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} className={"cap-switch " + (checked ? "on" : "")} onClick={onClick}><span/></button>;
}
function Pager({ total, page, busy, onChange }: { total: number; page: number; busy: boolean; onChange: (n: number) => void }) {
  return <div className="mcp-pager"><span>共 {total} 条 · 第 {page} 页</span><button className="cap-secondary" disabled={busy || page <= 1} onClick={() => onChange(page - 1)}>上一页</button><button className="cap-secondary" disabled={busy || page * 20 >= total} onClick={() => onChange(page + 1)}>下一页</button></div>;
}
