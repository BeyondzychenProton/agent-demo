import { useRef, useState, type FormEvent } from "react";
import { apiFetch } from "./api";

export function ChangePasswordButton() {
  return <PasswordChange />;
}

export function RequiredPasswordChange({ onComplete, onLogout }: {
  onComplete: () => Promise<void>;
  onLogout: () => void;
}) {
  return <PasswordChange required={{ onComplete, onLogout }} />;
}

function PasswordChange({ required }: {
  required?: { onComplete: () => Promise<void>; onLogout: () => void };
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState("");
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending || saved) return;
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
      if (required) {
        await required.onComplete();
        return;
      }
      setSaved(true);
      setMessage("密码已修改，其他登录会话已退出");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "密码修改失败，请重试");
    } finally {
      setPending(false);
    }
  }

  const form = <form onSubmit={event => void submit(event)} className="grid gap-3">
    <h2 id="change-password-heading" className="text-base font-semibold">修改密码</h2>
    {required && <p className="text-sm text-muted-foreground">
      首次使用初始密码登录，请先设置新密码。
    </p>}
    {!saved && <>
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
    </>}
    {message && <p role={saved ? "status" : "alert"} className="text-sm">{message}</p>}
    <div className="flex justify-end gap-3 text-sm">
      <button type="button" disabled={pending} onClick={required ? required.onLogout : () => dialog.current?.close()}>
        {required ? "退出登录" : saved ? "完成" : "取消"}
      </button>
      {!saved && <button type="submit" disabled={pending}
        className="rounded bg-primary px-3 py-2 text-primary-foreground">{pending ? "保存中…" : required ? "保存并进入系统" : "保存密码"}</button>}
    </div>
  </form>;

  if (required) {
    return <main className="flex min-h-screen items-center justify-center p-6">
      <div className="glass-panel w-full max-w-md rounded-3xl p-8">{form}</div>
    </main>;
  }
  return <>
    <button type="button" className="shrink-0 text-xs text-muted-foreground hover:text-foreground"
      onClick={() => dialog.current?.showModal()}>修改密码</button>
    <dialog ref={dialog} aria-labelledby="change-password-heading"
      className="m-auto w-[min(90vw,24rem)] rounded-xl border bg-background p-5 text-foreground shadow-xl backdrop:bg-black/40"
      onCancel={event => { if (pending) event.preventDefault(); }}
      onClose={() => {
        dialog.current?.querySelector("form")?.reset();
        setMessage("");
        setSaved(false);
      }}>
      {form}
    </dialog>
  </>;
}
