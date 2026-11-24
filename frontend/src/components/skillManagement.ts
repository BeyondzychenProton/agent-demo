import { apiFetch } from "@/auth/api";

export type ManagedSkill = {
  name: string; displayName: string | null; description: string; status: string | null;
  visibleScope: string; ownerId: string | null; ownerName: string | null; updatedAt: string | null;
  currentVersionId: string | null; platformVersion: string | null; versionNo: number | null;
  draftVersionId: string | null; draftVersionNo: number | null; dependencyCount: number; mappedCount: number; boundCount: number;
  dependenciesTracked: boolean;
};
export type SkillCatalogPage = {
  items: ManagedSkill[]; total: number; page: number; size: number;
  counts: Record<"all" | "enabled" | "disabled" | "draft", number>;
};
export type BoundSkill = {
  name: string; description: string; status: string; visibleScope: string;
  versionId: string | null; platformVersion: string | null; versionNo: string | null;
};
export function releaseLabel(version: { platformVersion?: string | null; versionNo?: string | number | null } | null | undefined) {
  return version?.platformVersion || (version?.versionNo ? `基线 #${version.versionNo}` : "尚未建立版本");
}
export function resourceLabel(skill: ManagedSkill) {
  if (!skill.dependenciesTracked) return "未登记依赖";
  if (!skill.dependencyCount) return "无声明依赖";
  return `${skill.mappedCount} / ${skill.dependencyCount} 已绑定`;
}
export async function skillRequest<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(url, init);
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { error?: string };
    throw new Error(body.error || `请求失败（${response.status}）`);
  }
  return response.status === 204 || response.headers.get("content-length") === "0" ? undefined as T : response.json() as Promise<T>;
}
