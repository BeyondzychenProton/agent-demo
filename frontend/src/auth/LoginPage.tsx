import { FormEvent, useState } from "react";
import { login, type AuthUser } from "@/auth/api";


export function LoginPage({ onSuccess }: { onSuccess: (user: AuthUser) => void }) {
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setPending(true);
    setError("");
    try {
      onSuccess(await login(phone.trim(), password));
    } catch (err) {
      setError(err instanceof Error ? err.message : "登录失败");
    } finally {
      setPending(false);
    }
  };

  return (
    <main className="flex min-h-screen items-center justify-center p-6">
      <form
        onSubmit={(event) => void submit(event)}
        className="glass-panel w-full max-w-md rounded-3xl p-8"
      >
        <h1 className="font-display text-2xl font-semibold tracking-tight">海卓大脑</h1>
        <p className="mt-2 text-sm text-muted-foreground">使用手机号登录以继续会话</p>
        <label className="mt-6 block text-xs text-muted-foreground">
          手机号
          <input
            className="mt-1 w-full rounded-xl border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-ring/30"
            value={phone}
            onChange={(event) => setPhone(event.target.value)}
            autoComplete="username"
            required
          />
        </label>
        <label className="mt-4 block text-xs text-muted-foreground">
          密码
          <input
            type="password"
            className="mt-1 w-full rounded-xl border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-ring/30"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        {error && <p className="mt-3 text-sm text-amber-700">{error}</p>}
        <button
          type="submit"
          disabled={pending}
          className="mt-6 w-full rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground disabled:opacity-60"
        >
          {pending ? "登录中…" : "登录"}
        </button>
      </form>
    </main>
  );
}
