export type AuthUser = {
  userId: number;
  name: string;
  phone: string;
  expiresAt?: number;
  token?: string;
};

async function parseJson<T>(response: Response): Promise<T> {
  const text = await response.text();
  if (!text) return {} as T;
  return JSON.parse(text) as T;
}

export async function fetchMe(): Promise<AuthUser | null> {
  const response = await fetch("/api/auth/me", { credentials: "include" });
  if (response.status === 401) return null;
  if (!response.ok) throw new Error("无法获取当前用户");
  return parseJson<AuthUser>(response);
}

export async function login(phone: string, password: string): Promise<AuthUser> {
  const response = await fetch("/api/auth/login", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone, password }),
  });
  if (response.status === 401) throw new Error("手机号或密码错误");
  if (!response.ok) {
    const body = await parseJson<{ error?: string }>(response);
    throw new Error(body.error || "登录失败");
  }
  return parseJson<AuthUser>(response);
}

export async function logout(): Promise<void> {
  await fetch("/api/auth/logout", { method: "POST", credentials: "include" });
}

/** 带 Cookie 的 fetch；401 时抛出 AuthExpiredError。 */
export async function apiFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  const response = await fetch(input, { ...init, credentials: "include" });
  if (response.status === 401) throw new AuthExpiredError();
  return response;
}

export class AuthExpiredError extends Error {
  constructor() {
    super("登录已失效");
    this.name = "AuthExpiredError";
  }
}
