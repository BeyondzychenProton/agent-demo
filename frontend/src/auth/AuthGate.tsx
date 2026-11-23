import { ReactNode, useCallback, useEffect, useState } from "react";
import { AuthExpiredError, fetchMe, logout, type AuthUser } from "@/auth/api";
import { LoginPage } from "@/auth/LoginPage";
import { RequiredPasswordChange } from "@/auth/ChangePasswordButton";

type AuthState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "authenticated"; user: AuthUser };

export function AuthGate({ children, demoLogin }: {
  children: (user: AuthUser, onLogout: () => void) => ReactNode;
  demoLogin?: Promise<AuthUser | null>;
}) {
  const [state, setState] = useState<AuthState>({ status: "loading" });

  const refresh = useCallback(async () => {
    try {
      const user = await (demoLogin ?? fetchMe());
      setState(user ? { status: "authenticated", user } : { status: "anonymous" });
    } catch {
      setState({ status: "anonymous" });
    }
  }, [demoLogin]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    const onUnhandled = (event: PromiseRejectionEvent) => {
      if (event.reason instanceof AuthExpiredError) {
        setState({ status: "anonymous" });
      }
    };
    window.addEventListener("unhandledrejection", onUnhandled);
    return () => window.removeEventListener("unhandledrejection", onUnhandled);
  }, []);

  const onLogout = async () => {
    await logout();
    sessionStorage.removeItem("session-chat.active-thread");
    setState({ status: "anonymous" });
  };

  if (state.status === "loading") {
    return <main className="auth-screen flex min-h-screen items-center justify-center text-sm text-muted-foreground">正在检查登录状态…</main>;
  }
  if (state.status === "anonymous") {
    if (demoLogin) return (
      <main className="grid min-h-screen place-content-center gap-4 p-6 text-center">
        <p>请从满天星演示入口打开海卓大脑。</p>
        <a className="rounded-lg bg-primary px-4 py-3 text-primary-foreground" href="/__h5-demo">打开演示</a>
      </main>
    );
    return <LoginPage onSuccess={(user) => setState({ status: "authenticated", user })} />;
  }
  if (state.user.mustChangePassword) {
    return <RequiredPasswordChange onComplete={refresh} onLogout={() => void onLogout()} />;
  }
  return <>{children(state.user, () => void onLogout())}</>;
}
