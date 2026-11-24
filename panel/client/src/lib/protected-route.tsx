import { Redirect, Route, useLocation, useSearch } from "wouter";
import { useAuth } from "@/hooks/use-auth";
import { Loader2 } from "lucide-react";
import { PANEL_HOME_PATH, PANEL_SIGN_IN_PATH, readReturnPath, signInPathReturningTo } from "@/lib/return-path";

interface ProtectedRouteProps {
  path: string;
  component: React.ComponentType;
}

export function ProtectedRoute({ path, component: Component }: ProtectedRouteProps) {
  const { user, isLoading } = useAuth();
  const [location] = useLocation();
  const search = useSearch();

  return (
    <Route path={path}>
      {(params) => {
        if (isLoading) {
          return (
            <div className="flex items-center justify-center min-h-screen">
              <Loader2 className="h-8 w-8 animate-spin text-primary" />
            </div>
          );
        }

        if (!user) {
          const signInPath = path.startsWith("/panel") ? PANEL_SIGN_IN_PATH : "/auth";
          return <Redirect to={signInPathReturningTo(signInPath, search ? `${location}?${search}` : location)} />;
        }

        return <Component {...params} />;
      }}
    </Route>
  );
}

interface AuthRouteProps {
  path: string;
  component: React.ComponentType;
}

export function AuthRoute({ path, component: Component }: AuthRouteProps) {
  const { user, isLoading } = useAuth();
  const search = useSearch();

  return (
    <Route path={path}>
      {(params) => {
        if (isLoading) {
          return (
            <div className="flex items-center justify-center min-h-screen">
              <Loader2 className="h-8 w-8 animate-spin text-primary" />
            </div>
          );
        }

        if (user) {
          return <Redirect to={readReturnPath(search) ?? (path.startsWith(PANEL_SIGN_IN_PATH) ? PANEL_HOME_PATH : "/")} />;
        }

        return <Component {...params} />;
      }}
    </Route>
  );
}