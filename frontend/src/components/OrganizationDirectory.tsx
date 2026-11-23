import { useEffect, useMemo, useState } from "react";
import { ChevronDown, ChevronLeft, ChevronRight, Search } from "lucide-react";
import { apiFetch } from "@/auth/api";

export type Department = { id: string; parentId: string | null; name: string; status: string };
export type Member = {
  id: string;
  departmentId: string | null;
  account: string;
  name: string;
  email: string | null;
  status: string;
};
export type Directory = { departments: Department[]; users: Member[] };

const PAGE_SIZE = 6;

function indexDirectory({ departments, users }: Directory) {
  const byId = new Map(departments.map(department => [department.id, department]));
  const children = new Map<string, Department[]>();
  for (const department of departments) {
    const parent = department.parentId ?? "";
    const siblings = children.get(parent) ?? [];
    siblings.push(department);
    children.set(parent, siblings);
  }

  const tree: { department: Department; depth: number }[] = [];
  const visited = new Set<string>();
  function visit(root: Department) {
    const stack = [{ department: root, depth: 0 }];
    while (stack.length) {
      const node = stack.pop()!;
      if (visited.has(node.department.id)) continue;
      visited.add(node.department.id);
      tree.push(node);
      for (const child of (children.get(node.department.id) ?? []).slice().reverse()) {
        stack.push({ department: child, depth: node.depth + 1 });
      }
    }
  }
  for (const department of departments) {
    if (!department.parentId || !byId.has(department.parentId)) visit(department);
  }
  for (const department of departments) visit(department);

  const membersByDepartment = new Map<string, Member[]>();
  for (const member of users) {
    let id = member.departmentId;
    const counted = new Set<string>();
    while (id && byId.has(id) && !counted.has(id)) {
      counted.add(id);
      const members = membersByDepartment.get(id) ?? [];
      members.push(member);
      membersByDepartment.set(id, members);
      id = byId.get(id)!.parentId;
    }
  }
  return { byId, tree, membersByDepartment };
}

export function OrganizationDirectory({ onDirectory, onMember, onDepartment }: { onDirectory?: (value: Directory) => void; onMember?: (member: Member) => void; onDepartment?: (id: string) => void }) {
  const [result, setResult] = useState<Directory | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [request, setRequest] = useState(0);
  const [departmentId, setDepartmentId] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    void apiFetch("/api/admin/directory", { signal: controller.signal })
      .then(response => {
        if (!response.ok) throw new Error(response.status === 403 ? "需要平台管理员权限" : "组织通讯录加载失败");
        return response.json() as Promise<Directory>;
      })
      .then(directory => {
        if (controller.signal.aborted) return;
        setResult(directory);
        onDirectory?.(directory);
        setDepartmentId(id => directory.departments.some(department => department.id === id) ? id : "");
        setPage(0);
        setLoading(false);
      })
      .catch((cause: Error) => {
        if (controller.signal.aborted) return;
        setError(cause.message);
        setLoading(false);
      });
    return () => controller.abort();
  }, [request]);

  const directory = useMemo(() => result ? indexDirectory(result) : null, [result]);
  if (!result || !directory || error) {
    return <div className="admin-card admin-empty" role="status">
      {loading ? "正在加载组织通讯录…" : error}
      {error && <button className="admin-link" onClick={() => setRequest(request + 1)}>重试</button>}
    </div>;
  }

  const search = query.trim().toLowerCase();
  const members = departmentId ? directory.membersByDepartment.get(departmentId) ?? [] : result.users;
  const filtered = members.filter(member => (!status || member.status === status)
    && [member.name, member.id, member.account, member.email].some(value => value?.toLowerCase().includes(search)));
  const first = page * PAGE_SIZE;

  function selectDepartment(id: string) {
    setDepartmentId(id);
    onDepartment?.(id);
    setPage(0);
  }

  return <div className="admin-workspace">
    <section className="admin-org admin-card">
      <h2>组织架构</h2>
      <div>
        <button onClick={() => selectDepartment("")} className={!departmentId ? "selected" : ""}>
          <span>全部成员</span><span>{result.users.length} 位</span>
        </button>
        {directory.tree.map(({ department, depth }) => <button key={department.id}
          onClick={() => selectDepartment(department.id)}
          className={departmentId === department.id ? "selected" : ""}>
          <span style={{ paddingInlineStart: Math.min(depth, 6) * 12 }}>
            {department.name}{department.status === "1" && <small> · 已停用</small>}
          </span>
          <span>{directory.membersByDepartment.get(department.id)?.length ?? 0} 位</span>
        </button>)}
      </div>
    </section>
    <section className="admin-members">
      <div className="admin-filters">
        <label className="admin-search"><Search size={16}/>
          <input value={query} onChange={event => { setQuery(event.target.value); setPage(0); }}
            placeholder="姓名、账号、用户 ID 或邮箱" aria-label="搜索成员"/>
        </label>
        <label className="admin-select">
          <select aria-label="筛选状态" value={status}
            onChange={event => { setStatus(event.target.value); setPage(0); }}>
            <option value="">全部状态</option><option value="0">已启用</option><option value="1">已停用</option>
          </select>
          <ChevronDown size={14}/>
        </label>
        <button className="admin-reset" disabled={loading} onClick={() => setRequest(request + 1)}>
          {loading ? "刷新中…" : "刷新"}
        </button>
      </div>
      <div className="admin-card admin-table-card">
        <div className="admin-table-title">{directory.byId.get(departmentId)?.name ?? "全部成员"}</div>
        <div className="admin-table-scroll">
          <table>
            <thead><tr><th>成员 / 用户 ID</th><th>部门</th><th>账号 / 邮箱</th><th>满天星账号状态</th><th>操作</th></tr></thead>
            <tbody>{filtered.slice(first, first + PAGE_SIZE).map(member => <tr key={member.id}>
              <td><strong>{member.name}</strong><small>{member.id}</small></td>
              <td>{member.departmentId ? directory.byId.get(member.departmentId)?.name ?? "部门暂不可用" : "未分配"}</td>
              <td><strong>{member.account}</strong>{member.email && <small>{member.email}</small>}</td>
              <td><span className={member.status === "0" ? "admin-green" : "admin-muted"}>
                {member.status === "0" ? "已启用" : "已停用"}
              </span></td><td><button className="admin-link" onClick={() => onMember?.(member)}>个人权限</button></td>
            </tr>)}</tbody>
          </table>
          {!filtered.length && <p className="admin-empty">{!result.users.length ? "暂无同步成员" : "没有找到匹配的成员"}</p>}
        </div>
        <div className="admin-pagination">
          <span>显示 {filtered.length ? first + 1 : 0}–{Math.min(first + PAGE_SIZE, filtered.length)}，共 {filtered.length} 位成员</span>
          <div>
            <button aria-label="上一页" disabled={!page} onClick={() => setPage(page - 1)}><ChevronLeft size={14}/></button>
            <button aria-label="下一页" disabled={first + PAGE_SIZE >= filtered.length}
              onClick={() => setPage(page + 1)}><ChevronRight size={14}/></button>
          </div>
        </div>
      </div>
    </section>
  </div>;
}
