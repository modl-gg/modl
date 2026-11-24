import { useEffect, useEffectEvent, useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from "react";
import { useLocation } from "wouter";
import { useTranslation } from "react-i18next";
import { noop } from "@tanstack/react-query";
import { FileText, Home, Loader2, Search, Settings, Ticket } from "lucide-react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { BrandLogo } from "@/components/brand/BrandLogo";
import { PlayerSearchFailure } from "@/components/player/PlayerSearchFailure";
import { usePlayerWindow } from "@/contexts/PlayerWindowContext";
import { usePunishmentLookup } from "@/hooks/use-player-lookup";
import { usePermissions, PERMISSIONS } from "@/hooks/use-permissions";
import { usePlayerSearch, type PlayerSearch } from "@/hooks/use-data";
import { useRecentPlayerSearches } from "@/hooks/use-recent-player-searches";
import { recentPunishmentsQuery, recentTicketsQuery } from "@/hooks/data/dashboard";
import { ticketsQuery } from "@/hooks/data/tickets";
import { punishmentTypesQuery } from "@/hooks/data/punishments";
import { queryClient } from "@/lib/queryClient";
import { isNavPathActive } from "@/lib/nav-active";
import { SETTINGS_ROOT_PATH } from "@/lib/settings-access";
import { LOOKUP_PATH } from "@/lib/player-links";
import { recentPlayerOf, type RecentPlayer } from "@/lib/recent-player-searches";
import { cn } from "@/lib/utils";
import { SourceCodeLink } from "./SourceCodeLink";

const VISIBLE_RECENT_SEARCHES = 5;
const CLOSE_DELAY_MS = 300;
const CLOSE_ANIMATION_MS = 100;
const NAV_ITEM_HEIGHT_PX = 56;
const NAV_PADDING_PX = 16;
const MIN_SIDEBAR_HEIGHT_PX = 200;
const PUNISHMENT_ID_PREFIX = "#";

interface NavItem {
  name: string;
  path: string;
  icon: ReactNode;
  permission?: string;
}

function prefetchRouteData(path: string, canPrefetchDashboard: boolean, canPrefetchPunishmentTypes: boolean) {
  switch (path) {
    case "/panel":
      if (canPrefetchDashboard) {
        queryClient.query(recentTicketsQuery(3)).catch(noop);
        queryClient.query(recentPunishmentsQuery(5)).catch(noop);
      }
      return;
    case "/panel/tickets":
      queryClient.query(ticketsQuery()).catch(noop);
      return;
    case SETTINGS_ROOT_PATH:
      if (canPrefetchPunishmentTypes) queryClient.query(punishmentTypesQuery()).catch(noop);
      return;
    default:
      return;
  }
}

function SectionLabel({ children }: { children: ReactNode }) {
  return <div className="mb-2 px-2 py-1 text-xs text-muted-foreground">{children}</div>;
}

function PlayerResult({ player, onSelect }: { player: RecentPlayer; onSelect: (player: RecentPlayer) => void }) {
  const { t } = useTranslation();
  return (
    <Button variant="ghost" className="mb-1 h-auto w-full justify-start px-3 py-2 text-xs" onClick={() => { onSelect(player); }}>
      <div className="flex flex-col items-start">
        <span className="font-medium">{player.username || t("search.unknown")}</span>
        <span className="text-[10px] text-muted-foreground">{player.isOnline ? t("search.online") : t("search.offline")}</span>
      </div>
    </Button>
  );
}

function LookupSpinner() {
  return (
    <div className="flex items-center justify-center py-8">
      <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
    </div>
  );
}

function RecentLookups({ players, onSelect }: { players: readonly RecentPlayer[]; onSelect: (player: RecentPlayer) => void }) {
  const { t } = useTranslation();
  if (players.length === 0) return null;
  return (
    <>
      <SectionLabel>{t("search.recentSearches")}</SectionLabel>
      {players.map((player) => <PlayerResult key={player.uuid} player={player} onSelect={onSelect} />)}
    </>
  );
}

function PlayerLookupResults({ search, query, recentPlayers, onSelect }: {
  search: PlayerSearch;
  query: string;
  recentPlayers: readonly RecentPlayer[];
  onSelect: (player: RecentPlayer) => void;
}) {
  const { t } = useTranslation();
  switch (search.state) {
    case "inactive":
      return <RecentLookups players={recentPlayers} onSelect={onSelect} />;
    case "pending":
      return <LookupSpinner />;
    case "failed":
      return <PlayerSearchFailure onRetry={search.retry} className="text-xs" />;
    case "settled":
      return search.players.length === 0 ? (
        <div className="py-3 text-center text-xs text-muted-foreground">{t("search.noPlayersFound", { query })}</div>
      ) : (
        <>
          <SectionLabel>{t("search.playersFound")}</SectionLabel>
          {search.players.map((player) => <PlayerResult key={player.uuid} player={recentPlayerOf(player)} onSelect={onSelect} />)}
        </>
      );
  }
}

function PunishmentLookupResults({ punishmentId, onOpen }: { punishmentId: string; onOpen: (playerUuid: string, punishmentId: string) => void }) {
  const { t } = useTranslation();
  const lookup = usePunishmentLookup(punishmentId);

  if (punishmentId === "") {
    return (
      <div className="rounded border border-muted/20 bg-muted/10 px-3 py-3 text-center text-xs text-muted-foreground">
        {t("search.enterPunishmentId")}
      </div>
    );
  }
  if (lookup.isLoading) return <LookupSpinner />;
  if (lookup.data !== undefined) {
    const result = lookup.data;
    return (
      <div className="mb-3">
        <SectionLabel>{t("search.punishmentFound")}</SectionLabel>
        <Button
          variant="ghost"
          className="mb-1 h-auto w-full justify-start border border-primary/20 bg-primary/10 px-3 py-3 text-xs hover:bg-primary/20"
          onClick={() => { onOpen(result.playerUuid, result.punishment.id); }}
        >
          <div className="flex w-full flex-col items-start">
            <span className="font-medium text-primary">{result.playerUsername || result.playerUuid || t("player.unknownPlayer")}</span>
            <span className="text-[10px] font-medium text-primary">{t("pages.lookup.punishmentIdLabel", { id: result.punishment.id })}</span>
          </div>
        </Button>
      </div>
    );
  }
  if (lookup.isError) {
    return (
      <div className="mb-3">
        <SectionLabel>{t("search.punishmentLookup")}</SectionLabel>
        <div className="rounded border border-destructive/20 bg-destructive/10 px-3 py-3 text-center text-xs text-destructive-text">
          {t("search.punishmentNotFound", { id: punishmentId })}
        </div>
      </div>
    );
  }
  return null;
}

type LookupPanelPhase = "closed" | "open" | "closing";
type LookupPanelMode = "preview" | "pinned";

function isInside(element: HTMLElement | null, target: EventTarget | null): boolean {
  return element !== null && target instanceof Node && element.contains(target);
}

function useLookupPanel() {
  const [phase, setPhase] = useState<LookupPanelPhase>("closed");
  const [mode, setMode] = useState<LookupPanelMode>("preview");
  const [query, setQuery] = useState("");
  const [focusRequest, setFocusRequest] = useState(0);
  const timerRef = useRef<number | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const panelId = useId();
  const isOpen = phase !== "closed";

  const cancelTimer = () => {
    if (timerRef.current === null) return;
    clearTimeout(timerRef.current);
    timerRef.current = null;
  };

  const schedule = (delayMs: number, action: () => void) => {
    cancelTimer();
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null;
      action();
    }, delayMs);
  };

  const close = () => {
    if (!isOpen) return;
    setPhase("closing");
    schedule(CLOSE_ANIMATION_MS, () => {
      setPhase("closed");
      setMode("preview");
      setQuery("");
    });
  };

  const openPinned = () => {
    cancelTimer();
    setPhase("open");
    setMode("pinned");
    setFocusRequest((request) => request + 1);
  };

  const toggle = () => {
    if (phase === "open" && mode === "pinned") close();
    else openPinned();
  };

  const preview = () => {
    cancelTimer();
    if (phase !== "open") setPhase("open");
  };

  const isEngaged = () => mode === "pinned" || query !== "" || document.activeElement === inputRef.current;

  const leave = () => {
    if (!isOpen || isEngaged()) return;
    schedule(CLOSE_DELAY_MS, close);
  };

  const closeOnEscape = (event: ReactKeyboardEvent) => {
    if (event.key !== "Escape" || !isOpen) return;
    event.stopPropagation();
    close();
    triggerRef.current?.focus();
  };

  useEffect(() => {
    if (focusRequest > 0) inputRef.current?.focus();
  }, [focusRequest]);

  const closeOutside = useEffectEvent((target: EventTarget | null) => {
    if (isInside(triggerRef.current, target) || isInside(panelRef.current, target)) return;
    close();
  });
  useEffect(() => {
    if (!isOpen) return undefined;
    const closeOnOutsidePointer = (event: PointerEvent) => { closeOutside(event.target); };
    document.addEventListener("pointerdown", closeOnOutsidePointer);
    return () => { document.removeEventListener("pointerdown", closeOnOutsidePointer); };
  }, [isOpen]);

  useEffect(() => () => {
    if (timerRef.current !== null) clearTimeout(timerRef.current);
  }, []);

  return {
    isOpen,
    isClosing: phase === "closing",
    query,
    setQuery,
    panelId,
    triggerRef,
    panelRef,
    inputRef,
    toggle,
    preview,
    leave,
    close,
    closeOnEscape,
  };
}

const Sidebar = ({ showSourceLink }: { showSourceLink: boolean }) => {
  const { t } = useTranslation();
  const { openPlayerWindow, openPlayerPunishment } = usePlayerWindow();
  const [location, navigate] = useLocation();
  const { hasPermission, isPermissionsLoading, canLoadSettingsData } = usePermissions();
  const [recentSearches, rememberPlayer] = useRecentPlayerSearches();
  const lookup = useLookupPanel();

  const trimmedQuery = lookup.query.trim();
  const isPunishmentLookup = trimmedQuery.startsWith(PUNISHMENT_ID_PREFIX);
  const playerSearch = usePlayerSearch(isPunishmentLookup ? "" : lookup.query);
  const recentPlayers = recentSearches.slice(0, VISIBLE_RECENT_SEARCHES).map(({ player }) => player);

  const selectPlayer = (player: RecentPlayer) => {
    if (player.uuid === "") return;
    rememberPlayer(player);
    openPlayerWindow(player.uuid, player.username);
    lookup.close();
  };

  const openPunishment = (playerUuid: string, punishmentId: string) => {
    openPlayerPunishment(playerUuid, punishmentId);
    lookup.close();
  };

  const navItems: NavItem[] = [
    { name: t("nav.home"), path: "/panel", icon: <Home className="h-5 w-5" /> },
    { name: t("nav.lookup"), path: LOOKUP_PATH, icon: <Search className="h-5 w-5" /> },
    { name: t("nav.tickets"), path: "/panel/tickets", icon: <Ticket className="h-5 w-5" /> },
    { name: t("nav.audit"), path: "/panel/audit", icon: <FileText className="h-5 w-5" />, permission: PERMISSIONS.ADMIN_AUDIT_VIEW },
    { name: t("nav.settings"), path: SETTINGS_ROOT_PATH, icon: <Settings className="h-5 w-5" /> },
  ];
  const visibleNavItems = navItems.filter((item) => item.permission === undefined || (!isPermissionsLoading && hasPermission(item.permission)));
  const sidebarHeight = Math.max(MIN_SIDEBAR_HEIGHT_PX, visibleNavItems.length * NAV_ITEM_HEIGHT_PX + NAV_PADDING_PX);

  const navigateTo = (path: string) => {
    lookup.close();
    navigate(path);
  };

  return (
    <>
      <div className={cn("fixed left-4 top-1/4 ml-4", lookup.isOpen ? "z-50" : "z-40")}>
        <div
          className="mb-3 w-16 rounded-2xl border border-sidebar-border/50 bg-sidebar/90 p-2 shadow-card-elevated transition-all duration-200 hover:bg-sidebar/95 hover:shadow-card-elevated"
          style={{ backdropFilter: "blur(12px)" }}
        >
          <BrandLogo icon="panel" wordmarkClassName="flex h-12 w-12 items-center justify-center text-sm" imageClassName="w-12 h-12 object-contain rounded-xl" />
        </div>

        <div className="flex">
          <aside
            className="h-auto w-16 overflow-hidden rounded-2xl border border-sidebar-border/50 bg-sidebar/90 shadow-card-elevated"
            style={{ backdropFilter: "blur(12px)", height: `${String(sidebarHeight)}px` }}
          >
            <nav className="w-16 p-2 py-4">
              <ul className="space-y-4">
                {visibleNavItems.map((item) => {
                  const isLookup = item.path === LOOKUP_PATH;
                  const isActive = isNavPathActive(item.path, location) || (isLookup && lookup.isOpen);
                  return (
                    <li key={item.path}>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <Button
                            ref={isLookup ? lookup.triggerRef : undefined}
                            variant={isActive ? "secondary" : "ghost"}
                            size="icon"
                            aria-label={item.name}
                            aria-expanded={isLookup ? lookup.isOpen : undefined}
                            aria-controls={isLookup && lookup.isOpen ? lookup.panelId : undefined}
                            className={cn("h-10 w-full", isActive && "bg-sidebar-primary/10 text-sidebar-primary hover:bg-sidebar-primary/20")}
                            onClick={isLookup ? lookup.toggle : () => { navigateTo(item.path); }}
                            onKeyDown={isLookup ? lookup.closeOnEscape : undefined}
                            onMouseEnter={isLookup ? lookup.preview : () => {
                              prefetchRouteData(item.path, hasPermission(PERMISSIONS.ADMIN_AUDIT_VIEW_DASHBOARD), canLoadSettingsData("punishmentTypes"));
                            }}
                            onMouseLeave={isLookup ? lookup.leave : undefined}
                          >
                            {item.icon}
                          </Button>
                        </TooltipTrigger>
                        <TooltipContent side="right">{item.name}</TooltipContent>
                      </Tooltip>
                    </li>
                  );
                })}
              </ul>
            </nav>
          </aside>

          {lookup.isOpen && (
            <div
              ref={lookup.panelRef}
              id={lookup.panelId}
              className={cn(
                "ml-2 overflow-hidden rounded-2xl border border-sidebar-border/50 bg-sidebar/90 shadow-card-elevated",
                lookup.isClosing ? "animate-slide-left" : "animate-slide-right",
              )}
              style={{ backdropFilter: "blur(12px)", height: `${String(sidebarHeight)}px` }}
              onMouseEnter={lookup.preview}
              onMouseLeave={lookup.leave}
              onKeyDown={lookup.closeOnEscape}
            >
              <div className="flex h-full w-[240px] flex-col p-3 pt-4">
                <Input
                  ref={lookup.inputRef}
                  placeholder={t("search.searchPlayers")}
                  aria-label={t("search.searchPlayers")}
                  className="mb-2 h-9 w-full rounded-md border border-sidebar-border bg-background/90 px-3 text-sm"
                  value={lookup.query}
                  onChange={(event) => { lookup.setQuery(event.target.value); }}
                />
                <div className="mb-3 px-1 text-xs text-muted-foreground">{t("search.searchPunishmentIds")}</div>
                <div className="flex-1 overflow-y-auto pr-1">
                  {isPunishmentLookup ? (
                    <PunishmentLookupResults punishmentId={trimmedQuery.slice(PUNISHMENT_ID_PREFIX.length)} onOpen={openPunishment} />
                  ) : (
                    <PlayerLookupResults search={playerSearch} query={trimmedQuery} recentPlayers={recentPlayers} onSelect={selectPlayer} />
                  )}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
      {showSourceLink && <SourceCodeLink className="fixed bottom-4 left-8 z-40 w-16 justify-center" />}
    </>
  );
};

export default Sidebar;
