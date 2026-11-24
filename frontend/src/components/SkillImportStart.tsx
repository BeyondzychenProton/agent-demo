import { useState, type InputHTMLAttributes } from "react";
import { ArrowLeft } from "lucide-react";
import { directoryArchive } from "./skillDirectory";

export function SkillImportStart({ onClose, onImport }: { onClose: () => void; onImport: (file: File) => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function upload(files: File[], directory: boolean) {
    if (!files.length || busy) return;
    setBusy(true); setError("");
    try {
      if (!directory && !files[0].name.toLowerCase().endsWith(".zip")) throw new Error("请选择 ZIP 文件");
      await onImport(directory ? await directoryArchive(files) : files[0]);
    } catch (failure) { setError(failure instanceof Error ? failure.message : typeof failure === "object" && failure && "message" in failure ? String(failure.message) : "导入失败，请重新选择"); }
    finally { setBusy(false); }
  }
  const directoryProps = { webkitdirectory: "", directory: "" } as InputHTMLAttributes<HTMLInputElement>;
  return <div className="capability skill-import-start">
    <div className="cap-detail-heading"><div><button className="cap-back" type="button" disabled={busy} onClick={onClose}><ArrowLeft size={15}/>返回列表</button><h2>导入 Skill 包</h2><p>保留原始内容，检查业务规则，映射环境资源后导入为草稿。</p></div><button className="cap-secondary" type="button" disabled={busy} onClick={onClose}>取消</button></div>
    <div className="cap-steps">{["选择文件", "检查与策略", "资源与规则", "导入结果"].map((step, index) => <div key={step} className={index === 0 ? "active" : ""}><span>{index + 1}</span>{step}</div>)}</div>
    <div className="skill-import-options">
      <section><h3>上传 ZIP 包</h3><div className="admin-card cap-panel"><h3>格式与限制</h3><dl><dt>压缩格式</dt><dd>普通 stored / deflate ZIP</dd><dt>文件上限</dt><dd>最多 50 个文件，展开总量不超过 2MB；ZIP 不超过 2MB</dd></dl></div><label className="cap-primary">选择 ZIP 文件<input aria-label="选择 ZIP 文件" type="file" accept=".zip" disabled={busy} hidden onChange={event => { const files = Array.from(event.target.files || []); event.target.value = ""; void upload(files, false); }}/></label></section>
      <section><h3>选择 Skill 目录</h3><div className="admin-card cap-panel"><h3>目录与限制</h3><dl><dt>入口与引用</dt><dd>SKILL.md 与 references 等目录</dd><dt>单文件</dt><dd>入口不超过 256KB，参考文件不超过 512KB</dd></dl></div><label className="cap-secondary">选择文件夹<input {...directoryProps} aria-label="选择 Skill 目录" type="file" multiple disabled={busy} hidden onChange={event => { const files = Array.from(event.target.files || []); event.target.value = ""; void upload(files, true); }}/></label></section>
    </div>
    {busy && <p role="status" className="cap-hint">正在上传并检查 Skill 包…</p>}{error && <p role="alert" className="cap-error">{error}</p>}
    <p className="cap-hint">选择 ZIP 包或完整 Skill 目录后，检查入口文件与业务规则，再确认资源映射并生成草稿。</p>
    <p className="cap-hint">生效顺序：导入草稿 → 检查依赖与规则 → 测试并发布 Skill → Agent 确认版本 → 用户单独授权。</p>
  </div>;
}
