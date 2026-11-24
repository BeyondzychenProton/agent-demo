import { useEffect, useRef, useState } from "react";
import { ArrowLeft, X } from "lucide-react";
import { apiFetch } from "@/auth/api";
import { SkillImportStart } from "./SkillImportStart";
import { SkillCatalog } from "./SkillCatalog";
import { SkillDialog } from "./SkillDialog";
import { releaseLabel, type ManagedSkill, type BoundSkill } from "./skillManagement";

type Department = { id: string; name: string; status: string };
type Check = { ruleKey: string; level: string; result: string; message: string; acknowledge: boolean };
type Blocker = { code: string; sourceKey: string | null; ruleKey: string | null; message: string };
type Dependency = {
  sourceKey: string;
  capabilityKey: string | null;
  required: boolean;
  gate: string | null;
  targetKind: string | null;
  targetProviderKey: string | null;
  targetToolKey: string | null;
  mappingState: string | null;
  reason: string | null;
};
type ImportView = {
  id: string;
  revision: string;
  state: string;
  step: string;
  filename: string;
  sourceAvailable: boolean;
  configuration: {
    targetName: string | null;
    entryPolicy: string | null;
    contentPolicy: string | null;
    visibleScope: string | null;
    ownerDeptId: number | null;
  } | null;
  package: { name: string | null; version: string | null; fileCount: number } | null;
  checks: Check[];
  dependencies: Dependency[];
  canConfirm: boolean;
  targetExists: boolean;
  blockers: Blocker[];
  result: {
    versionId: string;
    skillName: string;
    versionState: string;
    platformVersion: string | null;
    warnings: string[];
  } | null;
};
type VersionFile = { id: string; path: string; kind: string; byteSize: number; textPreviewAvailable: boolean };
type VersionView = {
  id: string;
  revision: string;
  skillName: string;
  versionNo: string | null;
  packageVersion: string | null;
  platformVersion: string | null;
  state: string;
  description: string;
  content: string;
  visibleScope: string;
  ownerDeptId: number | null;
  files: VersionFile[];
  dependencies: Dependency[];
  checks: Check[];
  displayName: string | null;
  importId: string | null;
};
type Overview = { item: ManagedSkill; current: VersionView | null; agent: { title: string; skills: BoundSkill[] } };
type VersionSummary = { id: string; skillName: string; versionNo: string; state: string; platformVersion: string | null };
type VersionPage = { items: VersionSummary[]; total: number; page: number; size: number };
type ResourceOption = {
  targetKind: string;
  providerKey: string | null;
  toolKey: string;
  displayName: string;
  capabilityKeys: string[];
  available: boolean;
};
type EditableFile = { fileId: string | null; path: string; kind: string; content: string; binary: boolean; dirty: boolean };
type Failure = { status: number; message: string; code?: string; details?: Record<string, unknown> };
type Route = { importId: string; versionId: string };

const steps = ["选择文件", "检查与策略", "资源与规则", "草稿结果"];

function readRoute(): Route {
  const params = new URLSearchParams(window.location.search);
  return {
    importId: params.get("import") || "",
    versionId: params.get("version") || "",
  };
}

function writeRoute(route: Route) {
  const url = new URL(window.location.href);
  url.searchParams.delete("import");
  url.searchParams.delete("version");
  url.searchParams.delete("draft");
  if (route.importId) url.searchParams.set("import", route.importId);
  if (route.versionId) url.searchParams.set("version", route.versionId);
  const next = url.pathname + url.search + url.hash;
  if (next !== window.location.pathname + window.location.search + window.location.hash) {
    window.history.pushState(null, "", next);
  }
}

async function readFailure(response: Response): Promise<Failure> {
  try {
    const body = await response.json() as { error?: string; code?: string; details?: Record<string, unknown> };
    return {
      status: response.status,
      message: body.error || `请求失败（${response.status}）`,
      code: body.code,
      details: body.details,
    };
  } catch {
    return { status: response.status, message: `请求失败（${response.status}）` };
  }
}

function currentRevision(failure: Failure, fallback: string) {
  const value = failure.details?.currentRevision;
  return typeof value === "string" && value ? value : fallback;
}

async function requestJson<T>(input: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(input, init);
  if (!response.ok) throw await readFailure(response);
  return response.json() as Promise<T>;
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

function stepIndex(step: string) {
  if (step === "REVIEW") return 1;
  if (step === "RESOURCES") return 2;
  if (step === "RESULT") return 3;
  return 0;
}

function Notice({ text, onClose }: { text: string; onClose: () => void }) {
  if (!text) return null;
  return <div className="cap-notice" role="status">{text}<button aria-label="关闭提示" type="button" onClick={onClose}><X size={14}/></button></div>;
}

function Checks({ checks }: { checks: Check[] }) {
  if (!checks.length) return null;
  return <ul className="cap-log">
    {checks.map(check => <li key={check.ruleKey}>{check.level === "ERROR" || check.result === "FAIL" ? "未通过" : "提示"} · {check.message}</li>)}
  </ul>;
}

export function SkillAdmin() {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [notice, setNotice] = useState("");
  const [route, setRoute] = useState<Route>(readRoute);
  const routeRef = useRef(route);
  routeRef.current = route;
  function go(next: Route, message = "") { writeRoute(next); setRoute(next); setNotice(message); }
  const close = () => go({ importId: "", versionId: "" });
  useEffect(() => {
    void apiFetch("/api/admin/directory")
      .then(async response => response.ok ? response.json() as Promise<{ departments: Department[] }> : { departments: [] })
      .then(directory => setDepartments(directory.departments.filter(item => item.status === "0")))
      .catch(() => setDepartments([]));
    const onPop = () => {
      const next = readRoute();
      const event = new CustomEvent("admin-before-navigate", { cancelable: true, detail: { resume: () => go(next) } });
      if (window.dispatchEvent(event)) setRoute(next);
      else writeRoute(routeRef.current);
    };
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);
  async function importFile(file: File) {
    const body = new FormData(); body.set("file", file);
    const created = await requestJson<ImportView>("/api/admin/skill-imports", {
      method: "POST", headers: { "Idempotency-Key": crypto.randomUUID().replace(/-/g, "") }, body,
    });
    go({ importId: created.id, versionId: "" });
  }
  async function edit(name: string) {
    const draft = await requestJson<VersionView>(`/api/admin/skills/${encodeURIComponent(name)}/draft`, { method: "POST" });
    go({ importId: "", versionId: draft.id });
  }
  if (route.importId === "new") return <SkillImportStart onClose={close} onImport={importFile}/>;
  if (route.importId) return <ImportWizard id={route.importId} departments={departments} notice={notice} setNotice={setNotice}
    onClose={close} onVersion={id => go({ importId: "", versionId: id })}/>;
  if (route.versionId) return <VersionEditor key={route.versionId} id={route.versionId} departments={departments} notice={notice}
    setNotice={setNotice} onClose={close} onImport={importFile} onVersion={id => go({ importId: "", versionId: id })}/>;
  return <SkillCatalog onEdit={edit} onImport={() => go({ importId: "new", versionId: "" })}/>;
}

function failureMessage(error: unknown) {
  if (typeof error === "object" && error && "message" in error && typeof error.message === "string") return error.message;
  return "请求失败";
}

function ImportWizard({ id, departments, notice, setNotice, onClose, onVersion }: {
  id: string;
  departments: Department[];
  notice: string;
  setNotice: (value: string) => void;
  onClose: () => void;
  onVersion: (id: string) => void;
}) {
  const [batch, setBatch] = useState<ImportView | null>(null);
  const [shown, setShown] = useState(0);
  const [targetName, setTargetName] = useState("");
  const [entryPolicy, setEntryPolicy] = useState("KEEP_ORIGINAL");
  const [contentPolicy, setContentPolicy] = useState("PRESERVE_PACKAGE");
  const [visibleScope, setVisibleScope] = useState("0");
  const [ownerDeptId, setOwnerDeptId] = useState("");
  const [dependencies, setDependencies] = useState<Dependency[]>([]);
  const [options, setOptions] = useState<ResourceOption[]>([]);
  const [ackExisting, setAckExisting] = useState(false);
  const [acks, setAcks] = useState<string[]>([]);
  const [readyId, setReadyId] = useState("");

  useEffect(() => {
    let cancelled = false;
    setBatch(null);
    void requestJson<ImportView>(`/api/admin/skill-imports/${id}`)
      .then(view => { if (!cancelled) setBatch(view); })
      .catch(error => { if (!cancelled) setNotice(failureMessage(error)); });
    return () => { cancelled = true; };
  }, [id]);

  useEffect(() => {
    if (!batch || batch.id === readyId) return;
    setReadyId(batch.id);
    setShown(stepIndex(batch.step));
    setTargetName(batch.configuration?.targetName || batch.package?.name || "");
    setEntryPolicy(batch.configuration?.entryPolicy || "KEEP_ORIGINAL");
    setContentPolicy(batch.configuration?.contentPolicy || "PRESERVE_PACKAGE");
    setVisibleScope(batch.configuration?.visibleScope || "0");
    setOwnerDeptId(batch.configuration?.ownerDeptId == null ? "" : String(batch.configuration.ownerDeptId));
    setDependencies(batch.dependencies || []);
    setAckExisting(false);
    setAcks([]);
  }, [batch, readyId]);

  useEffect(() => {
    if (!batch || stepIndex(batch.step) < 2) return;
    let cancelled = false;
    void Promise.all([loadOptions("PLATFORM"), loadOptions("MCP_TOOL")])
      .then(([platform, mcp]) => { if (!cancelled) setOptions([...platform, ...mcp]); })
      .catch(error => { if (!cancelled) setNotice(failureMessage(error)); });
    return () => { cancelled = true; };
  }, [batch?.id, batch?.step]);

  function apply(next: ImportView, message: string) {
    setBatch(next);
    setShown(stepIndex(next.step));
    setDependencies(next.dependencies || []);
    setNotice(message);
  }

  async function saveConfig() {
    if (!batch) return;
    if (entryPolicy === "KEEP_ORIGINAL" && contentPolicy === "REFERENCE_ONLY") {
      setNotice("保留原入口时不能只引用、不保存包内文件");
      return;
    }
    try {
      const next = await requestJson<ImportView>(`/api/admin/skill-imports/${batch.id}/configuration`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          revision: batch.revision,
          targetName,
          entryPolicy,
          contentPolicy,
          visibleScope,
          ownerDeptId: visibleScope === "1" && ownerDeptId ? Number(ownerDeptId) : null,
        }),
      });
      apply(next, "检查已保存");
    } catch (error) {
      keepInput(error, batch, setBatch, setNotice);
    }
  }

  async function saveDependencies() {
    if (!batch) return;
    if (dependencies.some(item => item.mappingState !== "CONFIRMED" && item.mappingState !== "PENDING")) {
      setNotice("每条依赖都要确认映射，或标明暂不接入");
      return;
    }
    try {
      const next = await requestJson<ImportView>(`/api/admin/skill-imports/${batch.id}/dependencies`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ revision: batch.revision, items: dependencies.map(payloadDependency) }),
      });
      apply(next, "依赖已保存");
    } catch (error) {
      keepInput(error, batch, setBatch, setNotice);
    }
  }

  async function confirm() {
    if (!batch) return;
    const keys = [...acks];
    if (ackExisting && !keys.includes("TARGET_EXISTS")) keys.push("TARGET_EXISTS");
    try {
      const next = await requestJson<ImportView>(`/api/admin/skill-imports/${batch.id}/confirm`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ revision: batch.revision, acknowledgeExisting: ackExisting, acknowledgedRuleKeys: keys }),
      });
      apply(next, "已生成草稿。正在使用的技能还没有变化。");
    } catch (error) {
      keepInput(error, batch, setBatch, setNotice);
    }
  }

  async function cancel() {
    if (!batch) return;
    try {
      const next = await requestJson<ImportView>(`/api/admin/skill-imports/${batch.id}/cancel`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ revision: batch.revision }),
      });
      apply(next, "导入已取消");
    } catch (error) {
      keepInput(error, batch, setBatch, setNotice);
    }
  }

  async function download() {
    if (!batch) return;
    const response = await apiFetch(`/api/admin/skill-imports/${batch.id}/source`);
    if (!response.ok) { setNotice((await readFailure(response)).message); return; }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = batch.filename || "package.bin";
    link.click();
    URL.revokeObjectURL(url);
  }

  const serverStep = batch ? stepIndex(batch.step) : 0;
  const active = Math.min(shown, serverStep);
  const ackChecks = (batch?.checks || []).filter(check => check.acknowledge && check.ruleKey !== "TARGET_EXISTS");
  return <div className="capability">
    <div className="cap-detail-heading"><div>
      <button className="cap-back" type="button" onClick={onClose}><ArrowLeft size={15}/> 返回列表</button>
      <h2>导入 {batch?.filename || "Skill 包"}</h2>
      <p>确认只会生成草稿。发布之后，正在使用的技能才会更新。</p>
    </div>{batch?.sourceAvailable && <button className="cap-secondary" type="button" onClick={() => void download()}>下载原包</button>}</div>
    <div className="cap-steps">{steps.map((label, index) => <div className={index === active ? "active" : index < serverStep ? "done" : ""} key={label}><span>{index + 1}</span>{label}</div>)}</div>
    <Notice text={notice} onClose={() => setNotice("")}/>
    {!batch ? <p className="admin-empty">正在读取导入批次…</p> : <div className="admin-card cap-panel">
      {active === 0 && <>
        <h3>选择文件</h3>
        <Checks checks={batch.checks || []}/>
        <p>{batch.state === "FAILED" ? "这个包无法继续，请重新选择文件。" : "文件已上传，可以进入检查。"}</p>
      </>}
      {active === 1 && <>
        <h3>检查与策略</h3>
        <Checks checks={batch.checks || []}/>
        <div className="cap-form cap-form-grid">
          <label>技能名 *<input aria-label="技能名" value={targetName} onChange={event => setTargetName(event.target.value)}/></label>
          <label>入口<select aria-label="入口策略" value={entryPolicy} onChange={event => {
            const next = event.target.value;
            setEntryPolicy(next);
            if (next === "KEEP_ORIGINAL" && contentPolicy === "REFERENCE_ONLY") setContentPolicy("PRESERVE_PACKAGE");
          }}><option value="KEEP_ORIGINAL">保留原入口</option><option value="PLATFORM_ENTRY">使用平台入口</option></select></label>
          <label>内容<select aria-label="内容策略" value={contentPolicy} onChange={event => setContentPolicy(event.target.value)}><option value="PRESERVE_PACKAGE">保存包内文件</option><option value="REFERENCE_ONLY" disabled={entryPolicy === "KEEP_ORIGINAL"}>只引用，不保存包内文件</option></select></label>
          <label>可见范围<select aria-label="可见范围" value={visibleScope} onChange={event => setVisibleScope(event.target.value)}><option value="0">公开</option><option value="1">本部门</option><option value="2">仅本人</option></select></label>
          <label>部门<select aria-label="部门" value={ownerDeptId} disabled={visibleScope !== "1"} onChange={event => setOwnerDeptId(event.target.value)}><option value="">请选择部门</option>{departments.map(department => <option key={department.id} value={department.id}>{department.name}</option>)}</select></label>
        </div>
      </>}
      {active === 2 && <>
        <h3>资源与规则</h3>
        <p className="cap-hint">建议映射还不能直接确认。每条依赖都要选定真实能力，或写明暂不接入的原因。</p>
        <DependencyEditor rows={dependencies} options={options} onChange={setDependencies}/>
        {batch.targetExists && <label className="cap-choice"><div><strong>同名技能已存在</strong><small>确认后只新增草稿，不覆盖当前内容</small></div><input type="checkbox" checked={ackExisting} onChange={event => setAckExisting(event.target.checked)}/></label>}
        {ackChecks.map(check => <label className="cap-choice" key={check.ruleKey}><div><strong>{check.message}</strong></div><input type="checkbox" checked={acks.includes(check.ruleKey)} onChange={event => setAcks(current => event.target.checked ? [...current, check.ruleKey] : current.filter(key => key !== check.ruleKey))}/></label>)}
        {(batch.blockers || []).map(blocker => <p className="cap-error" key={`${blocker.code}-${blocker.sourceKey || blocker.ruleKey}`}>{blocker.message}</p>)}
      </>}
      {active === 3 && <>
        <h3>草稿结果</h3>
        {batch.result ? <>
          <div className="cap-success">已生成草稿 {batch.result.skillName}，状态 {batch.result.versionState === "DRAFT" ? "草稿" : batch.result.versionState}</div>
          {(batch.result.warnings || []).map(warning => <p className="cap-hint" key={warning}>{warning}</p>)}
          <button className="cap-primary" type="button" onClick={() => onVersion(batch.result!.versionId)}>编辑并发布草稿</button>
        </> : <p>这个批次还没有草稿结果。</p>}
      </>}
    </div>}
    <div className="cap-wizard-actions">
      <button className="cap-secondary" type="button" onClick={() => active > 0 && active <= serverStep ? setShown(active - 1) : onClose()}>{active > 0 && active <= serverStep ? "上一步" : "返回"}</button>
      {batch && batch.state !== "CONFIRMED" && batch.state !== "CANCELLED" && batch.state !== "EXPIRED" && <button className="cap-secondary" type="button" onClick={() => void cancel()}>取消导入</button>}
      {active === 1 && <button className="cap-primary" type="button" onClick={() => void saveConfig()}>保存检查</button>}
      {active === 2 && <button className="cap-secondary" type="button" onClick={() => void saveDependencies()}>保存依赖</button>}
      {active === 2 && <button className="cap-primary" type="button" disabled={!batch?.canConfirm} onClick={() => void confirm()}>生成草稿</button>}
      {active < serverStep && <button className="cap-primary" type="button" onClick={() => setShown(active + 1)}>下一步</button>}
    </div>
  </div>;
}

function VersionEditor({ id, departments, notice, setNotice, onClose, onImport, onVersion }: {
  id: string;
  departments: Department[];
  notice: string;
  setNotice: (value: string) => void;
  onClose: () => void;
  onImport: (file: File) => Promise<void>;
  onVersion: (id: string) => void;
}) {
  const [version, setVersion] = useState<VersionView | null>(null);
  const [displayName, setDisplayName] = useState("");
  const [overview, setOverview] = useState<Overview | null>(null);
  const [history, setHistory] = useState<VersionSummary[]>([]);
  const [tab, setTab] = useState("基本信息");
  const [dialog, setDialog] = useState<"source" | "publish" | "leave" | "reimport" | null>(null);
  const [source, setSource] = useState("");
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState("");
  const pendingLeave = useRef<(() => void) | null>(null);
  const [description, setDescription] = useState("");
  const [content, setContent] = useState("");
  const [visibleScope, setVisibleScope] = useState("0");
  const [ownerDeptId, setOwnerDeptId] = useState("");
  const [files, setFiles] = useState<EditableFile[]>([]);
  const [dependencies, setDependencies] = useState<Dependency[]>([]);
  const [options, setOptions] = useState<ResourceOption[]>([]);
  const [platformVersion, setPlatformVersion] = useState("");
  const [acks, setAcks] = useState<string[]>([]);
  const [validation, setValidation] = useState<string[]>([]);
  const [readyId, setReadyId] = useState("");

  useEffect(() => {
    let cancelled = false;
    setVersion(null);
    void requestJson<VersionView>(`/api/admin/skill-versions/${id}`)
      .then(async view => {
        const editable = await loadEditableFiles(view);
        if (cancelled) return;
        setVersion(view);
        setFiles(editable);
        const data = await requestJson<Overview>(`/api/admin/skills/${encodeURIComponent(view.skillName)}/overview`);
        if (!cancelled) setOverview(data);
        const versions = await requestJson<VersionPage>(`/api/admin/skill-versions?skillName=${encodeURIComponent(view.skillName)}&page=1&size=100`);
        if (!cancelled) setHistory(versions.items);
      })
      .catch(error => { if (!cancelled) setNotice(failureMessage(error)); });
    void Promise.all([loadOptions("PLATFORM"), loadOptions("MCP_TOOL")])
      .then(([platform, mcp]) => { if (!cancelled) setOptions([...platform, ...mcp]); })
      .catch(() => { if (!cancelled) setOptions([]); });
    return () => { cancelled = true; };
  }, [id]);

  useEffect(() => {
    if (!version || version.id === readyId) return;
    setReadyId(version.id);
    setDisplayName(version.displayName || "");
    setDescription(version.description || "");
    setContent(version.content || "");
    setVisibleScope(version.visibleScope || "0");
    setOwnerDeptId(version.ownerDeptId == null ? "" : String(version.ownerDeptId));
    setDependencies(version.dependencies || []);
    setPlatformVersion(version.platformVersion || "");
  }, [version, readyId]);

  const draft = version?.state === "DRAFT";
  const fingerprint = JSON.stringify({ displayName, description, content, visibleScope, ownerDeptId, files, dependencies });
  const dirty = Boolean(draft && saved && fingerprint !== saved);
  useEffect(() => { if (readyId && !saved) setSaved(fingerprint); }, [readyId, saved, fingerprint]);
  useEffect(() => {
    const before = (event: Event) => {
      if (!dirty) return;
      event.preventDefault();
      pendingLeave.current = (event as CustomEvent<{ resume: () => void }>).detail.resume;
      setDialog("leave");
    };
    const unload = (event: BeforeUnloadEvent) => { if (dirty) event.preventDefault(); };
    window.addEventListener("admin-before-navigate", before);
    window.addEventListener("beforeunload", unload);
    return () => { window.removeEventListener("admin-before-navigate", before); window.removeEventListener("beforeunload", unload); };
  }, [dirty]);
  function leave(action: () => void) {
    if (!dirty) { action(); return; }
    pendingLeave.current = action; setDialog("leave");
  }
  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    try { await action(); } catch (error) { setNotice(failureMessage(error)); }
    finally { setBusy(false); }
  }
  async function refreshOverview(name: string) {
    setOverview(await requestJson<Overview>(`/api/admin/skills/${encodeURIComponent(name)}/overview`));
    const page = await requestJson<VersionPage>(`/api/admin/skill-versions?skillName=${encodeURIComponent(name)}&page=1&size=100`);
    setHistory(page.items);
  }
  async function showSource() {
    if (!version) return;
    const entry = version.files.find(file => file.kind === "SKILL");
    if (!entry) { setNotice("此版本没有保留入口原文。"); return; }
    const response = await apiFetch(`/api/admin/skill-versions/${version.id}/files/${entry.id}`);
    if (!response.ok) throw await readFailure(response);
    setSource(await response.text()); setDialog("source");
  }

  async function persist() {
    if (!version) return null;
    try {
      const next = await requestJson<VersionView>(`/api/admin/skill-versions/${version.id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          revision: version.revision,
          displayName,
          description,
          content,
          visibleScope,
          ownerDeptId: visibleScope === "1" && ownerDeptId ? Number(ownerDeptId) : null,
          dependencies: dependencies.map(payloadDependency),
          files: files.filter(file => file.kind !== "SKILL").map(filePayload),
        }),
      });
      const editable = await loadEditableFiles(next);
      setVersion(next);
      setFiles(editable);
      setDisplayName(next.displayName || "");
      setDescription(next.description || "");
      setContent(next.content || "");
      setVisibleScope(next.visibleScope || "0");
      setOwnerDeptId(next.ownerDeptId == null ? "" : String(next.ownerDeptId));
      setDependencies(next.dependencies || []);
      setReadyId(next.id);
      setSaved(JSON.stringify({ displayName: next.displayName || "", description: next.description || "", content: next.content || "",
        visibleScope: next.visibleScope || "0", ownerDeptId: next.ownerDeptId == null ? "" : String(next.ownerDeptId),
        files: editable, dependencies: next.dependencies || [] }));
      await refreshOverview(next.skillName);
      return next;
    } catch (error) {
      if (isConflict(error)) {
        setNotice("记录已被其他操作修改。你的输入已保留，请刷新核对最新草稿后再保存。");
        return null;
      }
      setNotice(failureMessage(error));
      return null;
    }
  }

  async function save() {
    const next = await persist();
    if (next) setNotice("草稿已保存。正在使用的技能没有变化。");
  }

  async function validate() {
    const current = await persist();
    if (!current) return;
    try {
      const result = await requestJson<{ canPublish: boolean; blockers: Blocker[]; warnings: string[] }>(`/api/admin/skill-versions/${current.id}/validate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ revision: current.revision }),
      });
      setValidation([
        ...(result.blockers || []).map(item => item.message),
        ...(result.warnings || []),
        result.canPublish ? "当前可以发布" : "当前还不能发布",
      ]);
    } catch (error) {
      if (isConflict(error)) {
        setNotice("记录已被其他操作修改。你的输入已保留，请刷新核对最新草稿。");
        return;
      }
      setNotice(failureMessage(error));
    }
  }

  async function publish() {
    const current = await persist();
    if (!current) return;
    try {
      const published = await requestJson<{ platformVersion: string; runtimeStatus: string }>(`/api/admin/skill-versions/${current.id}/publish`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ revision: current.revision, platformVersion, acknowledgedRuleKeys: acks }),
      });
      const next = await requestJson<VersionView>(`/api/admin/skill-versions/${current.id}`);
      setDialog(null);
      await refreshOverview(next.skillName);
      setVersion(next);
      setReadyId(next.id);
      setNotice(published.runtimeStatus === "1"
        ? `已发布 ${published.platformVersion}。当前仍为停用状态，请回列表启用。`
        : `已发布 ${published.platformVersion}。已有 Agent 固定引用保持不变，请在 Agent 管理中明确升级。`);
    } catch (error) {
      if (isConflict(error)) {
        setNotice("记录已被其他操作修改。你的输入已保留，请刷新核对最新草稿。");
        return;
      }
      setNotice(failureMessage(error));
    }
  }

  const ackChecks = (version?.checks || []).filter(check => check.acknowledge);
  const current = overview?.current;
  const held = overview?.agent.skills.filter(skill => skill.name === version?.skillName) || [];
  const confirmed = dependencies.filter(row => row.mappingState === "CONFIRMED" && row.targetKind !== "UNRESOLVED");
  return <div className="capability skill-editor">
    <div className="cap-detail-heading"><div><h2>{displayName || version?.skillName || "技能草稿"}</h2>
      <p>{version?.skillName} / 当前生效 {!overview ? "读取中…" : current ? releaseLabel(current) : "未发布"} / 负责人 {overview?.item.ownerName || (overview?.item.ownerId ? `用户 #${overview.item.ownerId}` : "未登记")}</p>
    </div><div className="cap-detail-actions">
      <button className="cap-secondary" type="button" disabled={busy} onClick={() => leave(onClose)}>返回列表</button>
      <button className="cap-secondary" type="button" disabled={!version || busy} onClick={() => void run(showSource)}>查看包原文</button>
      {draft && <button className="cap-secondary" type="button" disabled={busy} onClick={() => setDialog("reimport")}>调整导入方式</button>}
      {draft && <button className="cap-secondary" type="button" disabled={busy} onClick={() => void run(save)}>保存草稿</button>}
      {draft && <button className="cap-primary" type="button" disabled={busy} onClick={() => void run(async () => { setDialog("publish"); await validate(); })}>发布新版本</button>}
      {version?.state === "PUBLISHED" && <button className="cap-primary" type="button" disabled={busy} onClick={() => void run(async () => {
        const next = await requestJson<VersionView>(`/api/admin/skills/${encodeURIComponent(version.skillName)}/draft`, { method: "POST" }); onVersion(next.id);
      })}>修改草稿</button>}
    </div></div>
    <Notice text={notice} onClose={() => setNotice("")}/>
    <div className="cap-detail-tabs" role="tablist" aria-label="技能详情">{["基本信息", "资源绑定", "调用结果", "变更记录"].map(label => <button key={label} type="button" role="tab" aria-selected={tab === label} className={tab === label ? "active" : ""} onClick={() => setTab(label)}>{label}</button>)}</div>
    {version && <>
      <p className="cap-hint">{draft ? `正在编辑待发布草稿${version.packageVersion ? `，原包版本 ${version.packageVersion}` : ""}。保存仅改变草稿。` : `正在查看已发布快照 ${releaseLabel(version)}，内容只读。`}</p>
      {current && draft && <p className="cap-hint">当前生效版本仍为 {releaseLabel(current)}；已有 Agent 保持各自固定引用，发布新版本不会自动升级。</p>}
    </>}
    {!version ? <p className="admin-empty">正在读取版本…</p> : <div className={tab === "基本信息" ? "cap-detail-grid" : "skill-detail-full"}>
      <section className="admin-card cap-panel">
        {tab === "基本信息" && <><h3>基本信息</h3><p>清晰描述能力用途，便于 Agent 选择与研发维护。</p>
          <div className="cap-form cap-form-grid">
            <label>Skill 名称<input aria-label="Skill 名称" value={displayName} placeholder={version.skillName} disabled={!draft || busy} onChange={event => setDisplayName(event.target.value)}/></label>
            <label>唯一标识<input aria-label="唯一标识" value={version.skillName} disabled/><small>创建后不可修改，用于调用与审计关联。</small></label>
            <label className="cap-full">能力描述 *<textarea aria-label="用途说明" value={description} disabled={!draft || busy} onChange={event => setDescription(event.target.value)}/></label>
            <label>可见范围<select aria-label="可见范围" value={visibleScope} disabled={!draft || busy} onChange={event => setVisibleScope(event.target.value)}><option value="0">公开</option><option value="1">本部门</option><option value="2">仅本人</option></select></label>
            <label>部门<select aria-label="部门" value={ownerDeptId} disabled={!draft || visibleScope !== "1" || busy} onChange={event => setOwnerDeptId(event.target.value)}><option value="">请选择部门</option>{departments.map(department => <option key={department.id} value={department.id}>{department.name}</option>)}</select></label>
          </div>
          <details className="skill-content-editor"><summary>正文与参考文件</summary>
            <div className="cap-form"><label>正文<textarea aria-label="技能正文" value={content} disabled={!draft || busy} onChange={event => setContent(event.target.value)}/></label></div>
            <h3>文件</h3>{files.map((file, index) => <div className="cap-form" key={file.fileId || `new-${index}`}>
              <label>路径<input aria-label={`文件路径 ${index + 1}`} value={file.path} disabled={!draft || busy || file.kind === "SKILL" || Boolean(file.fileId && file.binary)} onChange={event => setFiles(rows => rows.map((item, i) => i === index ? { ...item, path: event.target.value, dirty: true } : item))}/></label>
              {file.kind === "SKILL" ? <p>入口原文保留只读，正文使用上面的编辑框维护。</p> : file.binary && file.fileId ? <p>二进制文件保留，无法在线编辑。</p> : <label>内容<textarea aria-label={`文件内容 ${index + 1}`} value={file.content} disabled={!draft || busy} onChange={event => setFiles(rows => rows.map((item, i) => i === index ? { ...item, content: event.target.value, dirty: true } : item))}/></label>}
              {draft && file.kind !== "SKILL" && <button className="admin-link" type="button" disabled={busy} onClick={() => setFiles(rows => rows.filter((_item, i) => i !== index))}>移除</button>}
            </div>)}
            {draft && <button className="cap-secondary" type="button" disabled={busy} onClick={() => setFiles(rows => [...rows, { fileId: null, path: "", kind: "REFERENCE", content: "", binary: false, dirty: true }])}>添加文本文件</button>}
          </details>
        </>}
        {tab === "资源绑定" && <>
          <h3>{draft ? "草稿资源绑定" : "版本资源绑定"}</h3>
          <p className={confirmed.length < dependencies.length ? "cap-hint cap-warning" : "cap-hint"}>{dependencies.length ? `${dependencies.length} 项声明依赖，${confirmed.length} 项映射已确认，${dependencies.length - confirmed.length} 项待接入或确认。发布时仍会重新检查实际资源。` : version.platformVersion || draft ? "此版本未声明资源依赖。" : "此基线未登记资源依赖，不代表资源已检查通过。"}</p>
          {current && draft && <p>当前生效版本 {releaseLabel(current)} 的资源映射与草稿独立，修改草稿不覆盖生效配置。</p>}
          <fieldset className="skill-dependencies" disabled={!draft || busy}><DependencyEditor rows={dependencies} options={options} onChange={setDependencies}/></fieldset>
          {current && draft && <details><summary>查看当前生效版本的依赖</summary><div className="skill-dependency-summary">{current.dependencies.length ? current.dependencies.map(row => <p key={row.sourceKey}>{row.sourceKey} → {row.targetProviderKey ? `${row.targetProviderKey} / ` : ""}{row.targetToolKey || "未映射"} · {row.mappingState === "CONFIRMED" ? "已确认" : "待接入"}</p>) : <p>未登记依赖</p>}</div></details>}
        </>}
        {tab === "调用结果" && <><h3>调用结果</h3><p className="admin-empty">调用结果数据未接入</p><p>近 24 小时调用量、成功率和执行中数量尚无管理端数据来源。</p></>}
        {tab === "变更记录" && <><h3>版本记录</h3><p>以下来自真实草稿与发布记录；完整操作审计未接入。</p>{history.map(row => <div className="cap-log skill-version-row" key={row.id}><div><strong>{row.state === "DRAFT" ? `草稿 #${row.versionNo}` : releaseLabel(row)}</strong><small>{row.id === overview?.item.currentVersionId ? "当前生效" : row.state === "PUBLISHED" ? "历史发布版" : "待发布"}</small></div><button className="admin-link" type="button" onClick={() => leave(() => onVersion(row.id))}>查看</button></div>)}</>}
      </section>
      {tab === "基本信息" && <aside className="skill-live-info"><h3>当前生效信息</h3><section className="admin-card cap-panel cap-side"><h3>当前发布版本</h3>
        <dl><div><dt>业务能力 ID</dt><dd>{version.skillName}</dd></div><div><dt>发布版本</dt><dd>{!overview ? "读取中…" : current ? releaseLabel(current) : "未发布"}</dd></div><div><dt>运行状态</dt><dd>{!overview ? "读取中…" : overview.item.status === "0" ? "已启用" : overview?.item.status === "1" ? "已停用" : "未发布"}</dd></div><div><dt>执行中</dt><dd>未接入</dd></div><div><dt>资源映射</dt><dd>{current ? current.platformVersion ? `${current.dependencies.filter(row => row.mappingState === "CONFIRMED").length} / ${current.dependencies.length}` : "未登记依赖" : "—"}</dd></div></dl>
      </section><section className="admin-card cap-panel cap-side"><h3>Agent 版本引用</h3>{held.length ? held.map(skill => <dl key={skill.name}><div><dt>Agent</dt><dd>{overview?.agent.title}</dd></div><div><dt>固定 Skill 版本</dt><dd>{releaseLabel(skill)}</dd></div></dl>) : <p>暂无 Agent 引用</p>}<p>发布新版本后，固定引用保持不变。</p><button className="admin-link" type="button" onClick={() => leave(() => window.dispatchEvent(new CustomEvent("admin-navigate", { detail: "Agent 管理" })))}>查看 Agent 管理</button></section></aside>}
    </div>}
    {dialog === "source" && <SkillDialog title="查看包原文 · 只读" onClose={() => setDialog(null)}><pre className="cap-source">{source}</pre></SkillDialog>}
    {dialog === "reimport" && <SkillDialog title="调整导入方式" busy={busy} onClose={() => setDialog(null)}><p>重新选择原始 Skill 包，在检查与策略中调整导入方式。这会生成新的草稿，保留当前生效版本和已有草稿。</p><label className="cap-primary">选择原包<input type="file" aria-label="重新导入原包" hidden accept=".md,.zip" disabled={busy} onChange={event => { const file = event.target.files?.[0]; event.target.value = ""; if (file) leave(() => { void run(() => onImport(file)); }); }}/></label></SkillDialog>}
    {dialog === "publish" && <SkillDialog title="发布新版本" busy={busy} onClose={() => setDialog(null)} footer={<><button className="cap-secondary" type="button" disabled={busy} onClick={() => setDialog(null)}>取消</button><button className="cap-primary" type="button" disabled={busy || !/^\d+\.\d+\.\d+$/.test(platformVersion)} onClick={() => void run(publish)}>{busy ? "处理中…" : "确认发布"}</button></>}>
      <p>发布将更新当前生效版本；已有 Agent 仍固定引用各自的版本，需另行明确升级。首次发布默认停用。</p>
      <div className="cap-form"><label>平台版本<input aria-label="平台版本" value={platformVersion} placeholder="1.0.0" disabled={busy} onChange={event => setPlatformVersion(event.target.value)}/></label></div>
      {ackChecks.map(check => <label className="cap-choice" key={check.ruleKey}><strong>{check.message}</strong><input type="checkbox" disabled={busy} checked={acks.includes(check.ruleKey)} onChange={event => setAcks(rows => event.target.checked ? [...rows, check.ruleKey] : rows.filter(key => key !== check.ruleKey))}/></label>)}
      {validation.map(item => <p className="cap-hint" key={item}>{item}</p>)}{notice && <p role="alert">{notice}</p>}
    </SkillDialog>}
    {dialog === "leave" && <SkillDialog title="有未保存的修改" busy={busy} onClose={() => setDialog(null)} footer={<><button className="cap-secondary" type="button" disabled={busy} onClick={() => setDialog(null)}>继续编辑</button><button className="cap-secondary" type="button" disabled={busy} onClick={() => { setDialog(null); pendingLeave.current?.(); }}>放弃未保存修改</button><button className="cap-primary" type="button" disabled={busy} onClick={() => void run(async () => { const next = await persist(); if (next) { setDialog(null); pendingLeave.current?.(); } })}>保存并离开</button></>}><p>已保存的草稿会保留。本次未保存修改可以先保存再离开。</p>{notice && <p role="alert">{notice}</p>}</SkillDialog>}
  </div>;
}

function DependencyEditor({ rows, options, onChange }: {
  rows: Dependency[];
  options: ResourceOption[];
  onChange: (rows: Dependency[]) => void;
}) {
  function patch(index: number, next: Partial<Dependency>) {
    onChange(rows.map((row, rowIndex) => rowIndex === index ? { ...row, ...next } : row));
  }
  if (!rows.length) return <p>这个包没有声明依赖。</p>;
  return <div>{rows.map((row, index) => {
    const choices = options.filter(option => option.available && option.targetKind === row.targetKind);
    return <div className="cap-form" key={row.sourceKey}>
      <p><strong>{row.sourceKey}</strong> · {row.required ? "必需" : "可选"} · {row.capabilityKey || "无能力要求"} · {row.mappingState === "SUGGESTED" ? "建议" : row.mappingState === "CONFIRMED" ? "已确认" : row.mappingState === "PENDING" ? "暂不接入" : "未确认"}</p>
      <label>处理方式<select aria-label={`${row.sourceKey} 处理方式`} value={row.mappingState === "PENDING" ? "PENDING" : row.mappingState === "CONFIRMED" ? "CONFIRMED" : "SUGGESTED"} onChange={event => {
        if (event.target.value === "PENDING") patch(index, { mappingState: "PENDING", targetKind: "UNRESOLVED", targetProviderKey: null, targetToolKey: null });
        else if (event.target.value === "CONFIRMED") patch(index, { mappingState: "CONFIRMED", targetKind: row.targetKind === "UNRESOLVED" || !row.targetKind ? "PLATFORM" : row.targetKind });
      }}><option value="SUGGESTED">请确认</option><option value="CONFIRMED">确认映射</option><option value="PENDING">暂不接入</option></select></label>
      {row.mappingState !== "PENDING" && <label>目标类型<select aria-label={`${row.sourceKey} 目标类型`} value={row.targetKind || "PLATFORM"} onChange={event => patch(index, { targetKind: event.target.value, targetProviderKey: null, targetToolKey: null, mappingState: "CONFIRMED" })}><option value="PLATFORM">平台能力</option><option value="MCP_TOOL">MCP 工具</option></select></label>}
      {row.mappingState !== "PENDING" && <label>目标能力<select aria-label={`${row.sourceKey} 目标能力`} value={optionValue(row)} onChange={event => {
        const selected = choices.find(option => optionValue(option) === event.target.value);
        patch(index, { mappingState: "CONFIRMED", targetProviderKey: selected?.providerKey || null, targetToolKey: selected?.toolKey || null });
      }}><option value="">请选择</option>{choices.map(option => <option key={optionValue(option)} value={optionValue(option)}>{option.displayName}</option>)}</select></label>}
      {row.mappingState === "PENDING" && <label>原因<input aria-label={`${row.sourceKey} 暂不接入原因`} value={row.reason || ""} onChange={event => patch(index, { reason: event.target.value })}/></label>}
    </div>;
  })}</div>;
}

function optionValue(option: { providerKey?: string | null; targetProviderKey?: string | null; toolKey?: string | null; targetToolKey?: string | null }) {
  return `${option.providerKey || option.targetProviderKey || ""}/${option.toolKey || option.targetToolKey || ""}`;
}

function payloadDependency(row: Dependency) {
  const pending = row.mappingState === "PENDING";
  return {
    sourceKey: row.sourceKey,
    targetKind: pending ? "UNRESOLVED" : row.targetKind,
    targetProviderKey: pending ? null : row.targetProviderKey,
    targetToolKey: pending ? null : row.targetToolKey,
    mappingState: pending ? "PENDING" : "CONFIRMED",
    reason: pending ? row.reason : null,
  };
}

function filePayload(file: EditableFile) {
  const payload: { fileId?: string; path: string; content?: string } = { path: file.path };
  if (file.fileId) payload.fileId = file.fileId;
  if (!file.fileId || (file.dirty && !file.binary)) payload.content = file.content;
  return payload;
}

async function loadOptions(kind: string) {
  const response = await apiFetch(`/api/admin/skill-resource-options?kind=${kind}&page=1&size=50`);
  if (response.status === 503) return [];
  if (!response.ok) throw await readFailure(response);
  const page = await response.json() as { items: ResourceOption[] };
  return page.items || [];
}

async function loadEditableFiles(version: VersionView): Promise<EditableFile[]> {
  const files: EditableFile[] = [];
  for (const file of version.files || []) {
    if (file.kind === "SKILL") {
      files.push({ fileId: file.id, path: file.path, kind: file.kind, content: "", binary: true, dirty: false });
      continue;
    }
    const response = await apiFetch(`/api/admin/skill-versions/${version.id}/files/${file.id}`);
    const type = response.headers.get("content-type") || "";
    if (!response.ok || !type.includes("text/plain")) {
      if (response.ok) await response.arrayBuffer();
      files.push({ fileId: file.id, path: file.path, kind: file.kind, content: "", binary: true, dirty: false });
      continue;
    }
    files.push({ fileId: file.id, path: file.path, kind: file.kind, content: await response.text(), binary: false, dirty: false });
  }
  return files;
}

function isConflict(error: unknown): error is Failure {
  return typeof error === "object" && error !== null && "code" in error && error.code === "REVISION_CONFLICT";
}

function keepInput(error: unknown, batch: ImportView, setBatch: (value: ImportView) => void, setNotice: (value: string) => void) {
  if (isConflict(error)) {
    setBatch({ ...batch, revision: currentRevision(error, batch.revision) });
    setNotice("记录已被修改。你的输入还在，请再保存一次。");
    return;
  }
  setNotice(failureMessage(error));
}
