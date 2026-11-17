import { ReactNode, useCallback, useEffect, useState } from "react";
import { AuthExpiredError, fetchMe, logout, type AuthUser } from "@/auth/api";
import { LoginPage } from "@/auth/LoginPage";
import { RequiredPasswordChange } from "@/auth/ChangePasswordButton";

type AuthState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "authenticated"; user: AuthUser };

export function AuthGate({ children }: { children: (user: AuthUser, onLogout: () => void) => ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading" });

  const refresh = useCallback(async () => {
    try {
      const user = await fetchMe();
      setState(user ? { status: "authenticated", user } : { status: "anonymous" });
    } catch {
      setState({ status: "anonymous" });
    }
  }, []);

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
    return <main className="flex min-h-screen items-center justify-center text-sm text-muted-foreground">正在检查登录状态…</main>;
  }
  if (state.status === "anonymous") {
    return <LoginPage onSuccess={(user) => setState({ status: "authenticated", user })} />;
  }
  if (state.user.mustChangePassword) {
    return <RequiredPasswordChange onComplete={refresh} onLogout={() => void onLogout()} />;
  }
  return <>{children(state.user, () => void onLogout())}</>;
}
