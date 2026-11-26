import { Suspense, lazy } from "react";
import { Switch, Route, useLocation } from "wouter";
import { useTranslation } from "react-i18next";
import { Toaster } from "@/components/ui/toaster";
import { TooltipProvider } from "@/components/ui/tooltip";
import Sidebar from "@/components/layout/Sidebar";
import MobileNavbar from "@/components/layout/MobileNavbar";
import { AuthProvider, useAuth } from "@/hooks/use-auth";
import { ProtectedRoute, AuthRoute } from "@/lib/protected-route";
import { useIsMobile } from '@/hooks/use-mobile';
import { useDocumentTitle } from "@/hooks/use-document-title";
import { usePublicSettings } from "@/hooks/use-public-settings";
import { useLanguagePreference } from "@/hooks/use-language-preference";
import { RealtimeProvider } from "@/hooks/use-realtime";
import { PlayerWindowProvider } from "@/contexts/PlayerWindowContext";
import { SetupGate } from "@/components/setup/SetupGate";
import { PanelNotices } from "@/components/layout/PanelNotices";
import { SecondFactorGate } from "@/components/auth/SecondFactorGate";
import { PageEndSourceCodeLink } from "@/components/layout/SourceCodeLink";
import { PublicMain } from "@/components/layout/PublicMain";
import { RouteErrorBoundary } from "@/components/layout/RouteErrorBoundary";
import { ReplayDialogHost } from "@/components/replays/ReplayDialogHost";
import { LoadingSpinner } from "@/components/ui/loading-spinner";
import { Button } from "@/components/ui/button";
import { isNavPathActive } from "@/lib/nav-active";
import { SETTINGS_ROOT_PATH } from "@/lib/settings-access";

const NotFound = lazy(async () => import("@/pages/not-found"));
const Home = lazy(async () => import("@/pages/home"));
const LookupPage = lazy(async () => import("@/pages/lookup-page"));
const PlayerDetailPage = lazy(async () => import("@/pages/player-detail-page"));
const Tickets = lazy(async () => import("@/pages/tickets"));
const TicketDetail = lazy(async () => import("@/pages/ticket-detail"));
const PlayerTicket = lazy(async () => import("@/pages/player-ticket"));
const Audit = lazy(async () => import("@/pages/audit"));
const Settings = lazy(async () => import("@/pages/settings"));
const AuthPage = lazy(async () => import("@/pages/auth-page"));
const AuthLinkPage = lazy(async () => import("@/pages/AuthLinkPage"));
const AppealsPage = lazy(async () => import("@/pages/appeals"));
const SubmitTicketPage = lazy(async () => import("@/pages/submit-ticket"));
const AcceptInvitationPage = lazy(async () => import("@/pages/AcceptInvitationPage"));
const RateLimitPage = lazy(async () => import("@/pages/RateLimitPage"));
const SetupPage = lazy(async () => import("@/pages/SetupPage"));
const UploadEvidencePage = lazy(async () => import("@/pages/upload-evidence-page"));
const ResetPasswordPage = lazy(async () => import("@/pages/ResetPasswordPage"));
const VerifyPage = lazy(async () => import("@/pages/VerifyPage"));
const ReplayPage = lazy(async () => import("@/pages/replay-page"));
const SharedReplayPage = lazy(async () => import("@/pages/shared-replay-page"));
const KnowledgebasePage = lazy(async () => import("@/pages/KnowledgebasePage"));
const ArticleDetailPage = lazy(async () => import("@/pages/ArticleDetailPage"));
const HomePage = lazy(async () => import("@/pages/HomePage"));

const STANDALONE_PATHS = ['/auth', '/panel/auth', '/auth/link', '/appeal'];
const STANDALONE_PATH_PREFIXES = ['/ticket/', '/submit-ticket', '/accept-invitation', '/upload-evidence', '/verify/', '/replay', '/rate-limit', '/setup', '/reset-password'];
const ALWAYS_REACHABLE_PATH_PREFIXES = ['/verify/', '/replay', '/setup', '/auth/link', '/reset-password'];
const SECOND_FACTOR_GATED_PATH_PREFIXES = ['/panel', '/setup', '/verify/'];

const SHARED_REPLAY_PATH_PREFIX = '/replay/';

const isSharedReplayPage = (location: string) => location.startsWith(SHARED_REPLAY_PATH_PREFIX);

const isStandalonePage = (location: string) =>
  STANDALONE_PATHS.includes(location) || STANDALONE_PATH_PREFIXES.some((prefix) => location.startsWith(prefix));

const isAlwaysReachablePath = (location: string) =>
  ALWAYS_REACHABLE_PATH_PREFIXES.some((prefix) => location.startsWith(prefix));

const needsSecondFactorGate = (location: string) =>
  location !== '/panel/auth' && SECOND_FACTOR_GATED_PATH_PREFIXES.some((prefix) => location.startsWith(prefix));

const isAuthPath = (location: string) => location === '/auth' || location.startsWith('/auth/') || location === '/panel/auth';

const needsSignedInProfile = (location: string) =>
  location.startsWith('/panel') || isAuthPath(location) || needsSecondFactorGate(location);

const SIGNED_OUT_WINDOW_SCOPE = 'signed-out';

const PageLoader = () => (
  <div className="flex items-center justify-center min-h-[200px]">
    <LoadingSpinner className="h-6 w-6 text-primary" />
  </div>
);

function PanelRoutes() {
  const [location] = useLocation();
  return (
    <RouteErrorBoundary resetKey={location}>
      <Suspense fallback={<PageLoader />}>
        <SetupGate>
          <PanelNotices />
          <Switch>
            <ProtectedRoute path="/panel" component={Home} />
            <ProtectedRoute path="/panel/lookup" component={LookupPage} />
            <ProtectedRoute path="/panel/player/:uuid" component={PlayerDetailPage} />
            <ProtectedRoute path="/panel/tickets" component={Tickets} />
            <ProtectedRoute path="/panel/tickets/:id" component={TicketDetail} />
            <ProtectedRoute path="/panel/audit" component={Audit} />
            <ProtectedRoute path="/panel/settings/:page?" component={Settings} />
            <Route component={NotFound} />
          </Switch>
        </SetupGate>
      </Suspense>
    </RouteErrorBoundary>
  );
}

function SharedReplayRoute() {
  const [location] = useLocation();
  return (
    <RouteErrorBoundary resetKey={location}>
      <Suspense fallback={<div className="h-dvh bg-black" />}>
        <Switch>
          <Route path="/replay/:token" component={SharedReplayPage} />
          <Route component={NotFound} />
        </Switch>
      </Suspense>
    </RouteErrorBoundary>
  );
}

function Router() {
  const [location] = useLocation();

  if (isSharedReplayPage(location)) return <SharedReplayRoute />;

  if (isStandalonePage(location)) {
    return (
      <PublicMain withSourceLink>
        <RouteErrorBoundary resetKey={location}>
          <Suspense fallback={<PageLoader />}>
            <Switch>
              <AuthRoute path="/auth" component={AuthPage} />
              <AuthRoute path="/panel/auth" component={AuthPage} />
              <Route path="/auth/link" component={AuthLinkPage} />
              <Route path="/setup" component={SetupPage} />
              <Route path="/setup/:step" component={SetupPage} />
              <Route path="/appeal" component={AppealsPage} />
              <Route path="/submit-ticket" component={SubmitTicketPage} />
              <Route path="/submit-ticket/:type" component={SubmitTicketPage} />
              <Route path="/ticket/:id" component={PlayerTicket} />
              <Route path="/accept-invitation" component={AcceptInvitationPage} />
              <Route path="/upload-evidence/:token" component={UploadEvidencePage} />
              <Route path="/verify/:token" component={VerifyPage} />
              <Route path="/replay" component={ReplayPage} />
              <Route path="/reset-password" component={ResetPasswordPage} />
              <Route path="/rate-limit" component={RateLimitPage} />
              <Route component={NotFound} />
            </Switch>
          </Suspense>
        </RouteErrorBoundary>
      </PublicMain>
    );
  }

  if (!location.startsWith("/panel")) {
    return (
      <PublicMain withSourceLink>
        <RouteErrorBoundary resetKey={location}>
          <Suspense fallback={<PageLoader />}>
            <Switch>
              <Route path="/" component={HomePage} />
              <Route path="/knowledgebase" component={KnowledgebasePage} />
              <Route path="/article/:articleSlug" component={ArticleDetailPage} />
              <Route component={NotFound} />
            </Switch>
          </Suspense>
        </RouteErrorBoundary>
      </PublicMain>
    );
  }

  return <PanelShell />;
}

function PanelShell() {
  const { user } = useAuth();
  const [location] = useLocation();
  const isMobile = useIsMobile();
  const isSettingsPage = isNavPathActive(SETTINGS_ROOT_PATH, location);

  return (
    <PlayerWindowProvider key={user?.id ?? SIGNED_OUT_WINDOW_SCOPE}>
      {user !== null && <ReplayDialogHost />}
      {isMobile ? (
        <div className="flex min-h-dvh flex-col bg-background">
          <main className="flex-1 bg-background pb-16">
            <PanelRoutes />
            {isSettingsPage && <PageEndSourceCodeLink />}
          </main>
          <MobileNavbar />
        </div>
      ) : (
        <div className="flex min-h-dvh bg-background">
          <Sidebar showSourceLink={isSettingsPage} />
          <main className="min-w-0 flex-1 bg-background pl-24">
            <PanelRoutes />
          </main>
        </div>
      )}
    </PlayerWindowProvider>
  );
}

function ServerUnreachable({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col items-center justify-center min-h-screen gap-4 px-4 text-center">
      <h1 className="text-xl font-semibold">{t('pages.unreachable.title')}</h1>
      <p className="text-muted-foreground max-w-md">{t('pages.unreachable.description')}</p>
      <Button type="button" onClick={onRetry}>{t('pages.unreachable.retry')}</Button>
    </div>
  );
}

function AppContent() {
  const { isLoading: isLoadingProfile, user, profileUnavailable, retryProfile } = useAuth();
  const {
    data: publicSettings,
    isLoading: isLoadingSettings,
    isError: isSettingsError,
    refetch: refetchSettings,
  } = usePublicSettings();
  const [location] = useLocation();

  useDocumentTitle();
  const languageReady = useLanguagePreference();

  const profileNeeded = needsSignedInProfile(location);

  if ((profileNeeded && isLoadingProfile) || isLoadingSettings || !languageReady) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <LoadingSpinner className="h-8 w-8 text-primary" />
      </div>
    );
  }

  const settingsUnavailable = isSettingsError && !publicSettings && !isAlwaysReachablePath(location);
  const signedInProfileUnavailable = profileNeeded && profileUnavailable;

  if (settingsUnavailable || signedInProfileUnavailable) {
    return (
      <ServerUnreachable
        onRetry={() => {
          if (settingsUnavailable) void refetchSettings();
          if (signedInProfileUnavailable) retryProfile();
        }}
      />
    );
  }

  const restricted = user !== null && user.secondFactor !== 'satisfied';

  if (restricted && needsSecondFactorGate(location)) {
    return (
      <>
        <Toaster />
        <SecondFactorGate />
      </>
    );
  }

  return (
    <>
      <RealtimeProvider />
      <Toaster />
      <Router />
    </>
  );
}

function App() {
  return (
    <AuthProvider>
      <TooltipProvider>
        <AppContent />
      </TooltipProvider>
    </AuthProvider>
  );
}

export default App;
