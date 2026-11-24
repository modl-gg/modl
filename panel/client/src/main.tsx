import { createRoot } from "react-dom/client";
import './lib/i18n';
import App from "./App";
import "./index.css";
import { QueryClientProvider } from "@tanstack/react-query";
import { queryClient } from "./lib/queryClient";
import { AppThemeProvider } from "./components/layout/AppThemeProvider";
import { Router } from "wouter";
import { registerStaleChunkRecovery } from "./lib/stale-chunk-recovery";
import { installHistoryGuard, useGuardedBrowserLocation } from "./lib/guarded-location";
import { applyInitialColorScheme } from "./lib/public-color-scheme";
import { publicPageOf } from "./utils/routes";

registerStaleChunkRecovery();
installHistoryGuard();
applyInitialColorScheme(publicPageOf(window.location.pathname) !== null);

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Missing #root element");
}

createRoot(rootElement).render(
  <QueryClientProvider client={queryClient}>
    <Router hook={useGuardedBrowserLocation}>
      <AppThemeProvider>
        <App />
      </AppThemeProvider>
    </Router>
  </QueryClientProvider>
);
