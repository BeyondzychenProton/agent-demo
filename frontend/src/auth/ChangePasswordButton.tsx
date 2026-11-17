import { useState, type FormEvent } from "react";
import { apiFetch } from "./api";

export function RequiredPasswordChange({ onComplete, onLogout }: {
  onComplete: () => Promise<void>;
  onLogout: () => void;
}) {
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const form = event.currentTarget;
    const data = new FormData(form);
    const currentPassword = String(data.get("currentPassword") ?? "");
    const newPassword = String(data.get("newPassword") ?? "");
    if (newPassword !== data.get("confirmation")) {
      setMessage("两次输入的新密码不一致");
      return;
    }
    if (newPassword === currentPassword || newPassword.trim().length === 0
        || new TextEncoder().encode(newPassword).length > 72) {
      setMessage("请使用不同于当前密码的新密码，长度至少 8 位，最多 72 字节");
      return;
    }

    setPending(true);
    setMessage("");
    try {
      const response = await apiFetch("/api/auth/password", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ currentPassword, newPassword }),
      });
      if (!response.ok) throw new Error(response.status === 400 ? "请检查当前密码和新密码" : "密码修改失败，请重试");
      form.reset();
      await onComplete();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "密码修改失败，请重试");
    } finally {
      setPending(false);
    }
  }

  return <main className="flex min-h-screen items-center justify-center p-6">
    <form onSubmit={event => void submit(event)} className="glass-panel grid w-full max-w-md gap-3 rounded-3xl p-8">
      <h2 className="text-base font-semibold">修改密码</h2>
      <p className="text-sm text-muted-foreground">首次使用初始密码登录，请先设置新密码。</p>
      <label className="grid gap-1 text-sm">当前密码
        <input className="rounded border bg-background p-2" type="password" name="currentPassword"
          autoComplete="current-password" required />
      </label>
      <label className="grid gap-1 text-sm">新密码
        <input className="rounded border bg-background p-2" type="password" name="newPassword"
          autoComplete="new-password" required minLength={8} />
      </label>
      <label className="grid gap-1 text-sm">确认新密码
        <input className="rounded border bg-background p-2" type="password" name="confirmation"
          autoComplete="new-password" required minLength={8} />
      </label>
      {message && <p role="alert" className="text-sm">{message}</p>}
      <div className="flex justify-end gap-3 text-sm">
        <button type="button" disabled={pending} onClick={onLogout}>
          退出登录
        </button>
        <button type="submit" disabled={pending}
          className="rounded bg-primary px-3 py-2 text-primary-foreground">{pending ? "保存中…" : "保存并进入系统"}</button>
      </div>
    </form>
  </main>;
}
