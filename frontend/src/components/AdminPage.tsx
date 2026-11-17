import { useState } from "react";
import { Activity, ArrowLeft, Blocks, Bot, Box, ChevronRight, ClipboardList, Info, LayoutDashboard, Settings, ShieldCheck, Sparkles, Users } from "lucide-react";
import type { AuthUser } from "@/auth/api";
import "./admin.css";
import { OrganizationDirectory } from "./OrganizationDirectory";
import { CapabilityManagement } from "./CapabilityManagement";

const menus = [
  { group: "工作空间", items: [{ name: "平台总览", icon: LayoutDashboard }] },
  { group: "能力与连接", items: [{ name: "Skills 管理", icon: Box }, { name: "MCP 管理", icon: Blocks }, { name: "Agent 管理", icon: Bot }] },
  { group: "管理", items: [{ name: "用户与权限", icon: Users }, { name: "审计日志", icon: ShieldCheck }, { name: "系统设置", icon: Settings }] },
];
const descriptions: Record<string,string> = { "平台总览":"查看平台运行情况与能力概览。", "Skills 管理":"管理平台的 Skills 能力。", "MCP 管理":"管理 MCP 服务与工具连接。", "Agent 管理":"管理智能体及其能力配置。", "审计日志":"查看平台操作与调用记录。", "系统设置":"管理平台的基础配置。" };

export function AdminPage({ user, onBack }: { user: AuthUser; onBack: () => void }) {
  const [page, setPage] = useState("用户与权限");
  const navigate = (action: () => void) => { const event = new CustomEvent("admin-before-navigate", { cancelable: true, detail: { resume: action } }); if (window.dispatchEvent(event)) action(); };
  return <main className="admin-app">
    <aside className="admin-sidebar">
      <div className="admin-brand"><div className="admin-brand-mark"><Sparkles size={21} strokeWidth={1.7}/></div><div><strong>海卓大脑</strong><span>HAIZHUO BRAIN</span></div></div>
      <nav className="admin-nav" aria-label="管理导航">{menus.map(group => <div className="admin-nav-group" key={group.group}><p>{group.group}</p>{group.items.map(({ name, icon: Icon }) => <button key={name} title={name} onClick={() => navigate(() => setPage(name))} className={page === name ? "active" : ""}><Icon size={17} strokeWidth={1.6}/><span>{name}</span></button>)}</div>)}</nav>
      <div className="admin-sidebar-footer"><div className="admin-sidebar-user"><span>{user.name?.slice(0,1) || "管"}</span><div><strong>{user.name || "管理员"}</strong><small>平台管理员</small></div></div><button onClick={() => navigate(onBack)} title="返回会话"><ArrowLeft size={15}/><span>返回会话</span></button></div>
    </aside>
    <div className="admin-main"><header className="admin-topbar"><div>能力与连接 <ChevronRight size={13}/> <span>{page}</span></div></header>
      <div className="admin-scroll"><div className="admin-content"><div className="admin-heading"><h1>{page}</h1><p>{page === "用户与权限" ? "查看满天星同步的组织架构与成员。" : descriptions[page]}</p></div>
      {page === "用户与权限" ? <OrganizationDirectory/> : page === "Skills 管理" || page === "MCP 管理" || page === "Agent 管理" ? <CapabilityManagement key={page} kind={page}/> : <MockSection page={page}/>}
      </div></div>
    </div>
  </main>;
}
function MockSection({page}:{page:string}) {
  const entries: Record<string,string[][]> = {
    "平台总览": [["Skills","8 个"],["MCP 服务","4 个"],["Agent","3 个"]],
    "Skills 管理": [["会议助手","会议预订与日程管理","已启用"],["知识库问答","检索与回答企业知识","已启用"],["文档总结","提炼文档中的关键信息","已启用"]],
    "MCP 管理": [["会议服务","提供会议室及会议预订工具","已连接"],["知识库服务","提供企业知识检索能力","已连接"],["日历服务","提供日程查询能力","已连接"]],
    "Agent 管理": [["通用助手","日常办公与信息查询","运行中"],["会议助手","会议安排与协作","运行中"],["知识助手","企业知识问答","运行中"]],
    "审计日志": [["陈智龙","查看用户权限","今天 10:32"],["赵敏","更新 Skill 配置","今天 09:15"],["李明","调用会议服务","昨天 17:24"]],
    "系统设置": [["平台名称","海卓大脑"],["默认语言","简体中文"],["时区","Asia/Shanghai"]],
  };
  return <section className={`admin-mock ${page === "平台总览" ? "overview" : ""}`}>{(entries[page] || []).map(row => <div key={row[0]} className="admin-card admin-mock-card"><div className="admin-mock-icon">{page === "平台总览" ? <Activity size={20}/> : <ClipboardList size={20}/>}</div><div><strong>{row[0]}</strong><p>{row[1]}</p></div>{row[2] && <span>{row[2]}</span>}</div>)}</section>;
}
