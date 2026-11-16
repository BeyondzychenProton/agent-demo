import { useState } from "react";
import { Activity, ArrowLeft, Blocks, Bot, Box, ChevronDown, ChevronLeft, ChevronRight, ClipboardList, Info, LayoutDashboard, Search, Settings, ShieldCheck, Sparkles, Users, X } from "lucide-react";
import type { AuthUser } from "@/auth/api";
import "./admin.css";
import { CapabilityManagement } from "./CapabilityManagement";

const menus = [
  { group: "工作空间", items: [{ name: "平台总览", icon: LayoutDashboard }] },
  { group: "能力与连接", items: [{ name: "Skills 管理", icon: Box }, { name: "MCP 管理", icon: Blocks }, { name: "Agent 管理", icon: Bot }] },
  { group: "管理", items: [{ name: "用户与权限", icon: Users }, { name: "审计日志", icon: ShieldCheck }, { name: "系统设置", icon: Settings }] },
];
type Member = { name: string; id: string; department: string; job: string; role: string; status: string; scope: string; grants: string };
const members: Member[] = [
  { name:"陈智龙", id:"EMP-1001", department:"研发部", job:"平台负责人", role:"平台管理员", status:"已启用", scope:"本人范围", grants:"2 项授权" },
  { name:"李明", id:"EMP-1002", department:"研发部", job:"研发工程师", role:"普通员工", status:"已启用", scope:"本人范围", grants:"3 项授权" },
  { name:"张伟", id:"EMP-1003", department:"研发部", job:"研发工程师", role:"普通员工", status:"已启用", scope:"本人范围", grants:"1 项授权" },
  { name:"王芳", id:"EMP-1004", department:"前端组", job:"前端工程师", role:"普通员工", status:"已启用", scope:"本人范围", grants:"2 项授权" },
  { name:"赵敏", id:"EMP-1005", department:"产品部", job:"产品负责人", role:"普通员工", status:"已启用", scope:"本部门范围", grants:"1 项授权" },
  { name:"周宁", id:"EMP-1006", department:"产品部", job:"产品经理", role:"普通员工", status:"已停用", scope:"本人范围", grants:"1 项授权 · 不可发起" },
  { name:"刘洋", id:"EMP-1007", department:"运营部", job:"运营专员", role:"普通员工", status:"已启用", scope:"本人范围", grants:"1 项授权" },
  { name:"孙悦", id:"EMP-1008", department:"运营部", job:"运营专员", role:"普通员工", status:"已启用", scope:"本人范围", grants:"2 项授权" },
  { name:"吴桐", id:"EMP-1009", department:"运营部", job:"运营负责人", role:"普通员工", status:"已启用", scope:"本部门范围", grants:"2 项授权" },
  { name:"郑琳", id:"EMP-1010", department:"人事行政部", job:"人事专员", role:"普通员工", status:"已启用", scope:"本人范围", grants:"1 项授权" },
  { name:"何平", id:"EMP-1011", department:"人事行政部", job:"行政专员", role:"普通员工", status:"已启用", scope:"本人范围", grants:"1 项授权" },
  { name:"徐静", id:"EMP-1012", department:"财务部", job:"财务专员", role:"普通员工", status:"已启用", scope:"本人范围", grants:"1 项授权" },
];
const departments = [["全部成员", 12], ["研发部", 4], ["产品部", 2], ["运营部", 3], ["人事行政部", 2], ["财务部", 1]] as const;
const descriptions: Record<string,string> = { "平台总览":"查看平台运行情况与能力概览。", "Skills 管理":"管理平台的 Skills 能力。", "MCP 管理":"管理 MCP 服务与工具连接。", "Agent 管理":"管理智能体及其能力配置。", "审计日志":"查看平台操作与调用记录。", "系统设置":"管理平台的基础配置。" };

export function AdminPage({ user, onBack }: { user: AuthUser; onBack: () => void }) {
  const [page, setPage] = useState("用户与权限");
  const navigate = (action: () => void) => { const event = new CustomEvent("admin-before-navigate", { cancelable: true, detail: { resume: action } }); if (window.dispatchEvent(event)) action(); };
  const [department, setDepartment] = useState("全部成员");
  const [query, setQuery] = useState("");
  const [role, setRole] = useState("全部角色");
  const [status, setStatus] = useState("全部状态");
  const [selected, setSelected] = useState<Member | null>(null);
  const [index, setIndex] = useState(0);
  const filtered = members.filter(m => (department === "全部成员" || m.department === department || department === "研发部" && m.department === "前端组") && (role === "全部角色" || m.role === role) && (status === "全部状态" || m.status === status) && Object.values(m).join(" ").toLowerCase().includes(query.toLowerCase()));
  const reset = () => { setQuery(""); setRole("全部角色"); setStatus("全部状态"); setDepartment("全部成员"); setIndex(0); };
  return <main className="admin-app">
    <aside className="admin-sidebar">
      <div className="admin-brand"><div className="admin-brand-mark"><Sparkles size={21} strokeWidth={1.7}/></div><div><strong>海卓大脑</strong><span>HAIZHUO BRAIN</span></div></div>
      <nav className="admin-nav" aria-label="管理导航">{menus.map(group => <div className="admin-nav-group" key={group.group}><p>{group.group}</p>{group.items.map(({ name, icon: Icon }) => <button key={name} title={name} onClick={() => navigate(() => setPage(name))} className={page === name ? "active" : ""}><Icon size={17} strokeWidth={1.6}/><span>{name}</span></button>)}</div>)}</nav>
      <div className="admin-sidebar-footer"><div className="admin-sidebar-user"><span>{user.name?.slice(0,1) || "管"}</span><div><strong>{user.name || "管理员"}</strong><small>平台管理员</small></div></div><button onClick={() => navigate(onBack)} title="返回会话"><ArrowLeft size={15}/><span>返回会话</span></button></div>
    </aside>
    <div className="admin-main"><header className="admin-topbar"><div>能力与连接 <ChevronRight size={13}/> <span>{page}</span></div></header>
      <div className="admin-scroll"><div className="admin-content"><div className="admin-heading"><h1>{page}</h1><p>{page === "用户与权限" ? "管理员工身份、两类平台角色与业务能力授权。" : descriptions[page]}</p></div>
      {page === "用户与权限" ? <><div className="admin-workspace"><section className="admin-org admin-card"><h2>组织架构</h2><div>{departments.map(([name,count]) => <button key={name} onClick={() => { setDepartment(name); setIndex(0); }} className={department === name ? "selected" : ""}><span>{name}</span><span>{count} 位{name === "研发部" && <small> · 包含前端组 1 位</small>}</span></button>)}</div></section>
        <section className="admin-members"><div className="admin-filters"><label className="admin-search"><Search size={16}/><input value={query} onChange={e => { setQuery(e.target.value); setIndex(0); }} placeholder="姓名、员工 ID 或邮箱" /></label><label className="admin-select"><select aria-label="筛选角色" value={role} onChange={e => { setRole(e.target.value); setIndex(0); }}><option>全部角色</option><option>平台管理员</option><option>普通员工</option></select><ChevronDown size={14}/></label><label className="admin-select"><select aria-label="筛选状态" value={status} onChange={e => { setStatus(e.target.value); setIndex(0); }}><option>全部状态</option><option>已启用</option><option>已停用</option></select><ChevronDown size={14}/></label><button className="admin-reset" onClick={reset}>重置</button></div>
          <div className="admin-card admin-table-card"><div className="admin-table-title">{department}</div><div className="admin-table-scroll"><table><thead><tr>{["成员 / 员工 ID", "部门 / 职位", "平台角色", "账号状态", "业务授权", "操作"].map(x => <th key={x}>{x}</th>)}</tr></thead><tbody>{filtered.slice(index * 6, index * 6 + 6).map(m => <tr key={m.id}><td><strong>{m.name}</strong><small>{m.id}</small></td><td><strong>{m.department}</strong><small>{m.job}</small></td><td><span className={m.role === "平台管理员" ? "admin-purple" : ""}>{m.role}</span></td><td><span className={m.status === "已启用" ? "admin-green" : "admin-muted"}>{m.status}</span></td><td><strong>{m.scope}</strong><small>{m.grants}</small></td><td><button className="admin-link" onClick={() => setSelected(m)}>权限</button></td></tr>)}</tbody></table>{!filtered.length && <p className="admin-empty">没有找到匹配的成员</p>}</div><div className="admin-pagination"><span>显示 {filtered.length ? index * 6 + 1 : 0}–{Math.min((index + 1) * 6, filtered.length)}，共 {filtered.length} 位成员</span><div><button aria-label="上一页" disabled={!index} onClick={() => setIndex(index - 1)}><ChevronLeft size={14}/></button>{Array.from({length: Math.ceil(filtered.length / 6)}, (_, i) => <button key={i} className={i === index ? "current" : ""} onClick={() => setIndex(i)}>{i+1}</button>)}<button aria-label="下一页" disabled={(index + 1) * 6 >= filtered.length} onClick={() => setIndex(index + 1)}><ChevronRight size={14}/></button></div></div></div>
        </section></div><p className="admin-footnote"><Info size={15}/> 通讯录同步维护组织身份；平台角色、业务能力和数据范围独立配置。管理员身份不自动获得他人会话数据权限。 <span>当前为演示数据</span></p></> : page === "Skills 管理" || page === "MCP 管理" || page === "Agent 管理" ? <CapabilityManagement key={page} kind={page}/> : <MockSection page={page}/>}
      </div></div>
    </div>
    {selected && <div className="admin-overlay" onClick={() => setSelected(null)}><div className="admin-drawer" onClick={e => e.stopPropagation()}><div className="admin-drawer-head"><div><h2>成员权限</h2><p>查看成员身份与业务授权</p></div><button onClick={() => setSelected(null)} aria-label="关闭"><X size={19}/></button></div><div className="admin-profile"><div className="admin-profile-avatar">{selected.name[0]}</div><div><strong>{selected.name}</strong><small>{selected.id} · {selected.department}</small></div></div><h3>基本信息</h3><dl>{[["部门 / 职位", `${selected.department} / ${selected.job}`],["平台角色",selected.role],["账号状态",selected.status],["业务授权",`${selected.scope} · ${selected.grants}`]].map(([k,v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl><div className="admin-drawer-note"><Info size={15}/> 此处展示的是演示数据</div></div></div>}
  </main>;
}
function MockSection({page}:{page:string}) {
  const entries: Record<string,string[][]> = {
    "平台总览": [["平台成员","12 位"],["Skills","8 个"],["MCP 服务","4 个"],["Agent","3 个"]],
    "Skills 管理": [["会议助手","会议预订与日程管理","已启用"],["知识库问答","检索与回答企业知识","已启用"],["文档总结","提炼文档中的关键信息","已启用"]],
    "MCP 管理": [["会议服务","提供会议室及会议预订工具","已连接"],["知识库服务","提供企业知识检索能力","已连接"],["日历服务","提供日程查询能力","已连接"]],
    "Agent 管理": [["通用助手","日常办公与信息查询","运行中"],["会议助手","会议安排与协作","运行中"],["知识助手","企业知识问答","运行中"]],
    "审计日志": [["陈智龙","查看用户权限","今天 10:32"],["赵敏","更新 Skill 配置","今天 09:15"],["李明","调用会议服务","昨天 17:24"]],
    "系统设置": [["平台名称","海卓大脑"],["默认语言","简体中文"],["时区","Asia/Shanghai"]],
  };
  return <section className={`admin-mock ${page === "平台总览" ? "overview" : ""}`}>{(entries[page] || []).map(row => <div key={row[0]} className="admin-card admin-mock-card"><div className="admin-mock-icon">{page === "平台总览" ? <Activity size={20}/> : <ClipboardList size={20}/>}</div><div><strong>{row[0]}</strong><p>{row[1]}</p></div>{row[2] && <span>{row[2]}</span>}</div>)}</section>;
}
