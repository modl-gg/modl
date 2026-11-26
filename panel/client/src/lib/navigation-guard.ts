export interface NavigationBlock {
  path: string;
  state?: unknown;
  onBlocked: () => void;
}

export interface RestoredLocation {
  readonly path: string;
  readonly state: unknown;
}

export interface NavigationGuard {
  activate: (block: NavigationBlock) => () => void;
  intercept: (target: string, currentHref: string) => boolean;
  restoreTarget: (currentHref: string) => RestoredLocation | null;
}

export function pathOf(target: string, currentHref: string): string {
  return new URL(target, currentHref).pathname;
}

export function createNavigationGuard(): NavigationGuard {
  const blocks: NavigationBlock[] = [];
  const current = (): NavigationBlock | undefined => blocks.at(-1);

  return {
    activate: (block) => {
      blocks.push(block);
      return () => {
        const index = blocks.indexOf(block);
        if (index !== -1) blocks.splice(index, 1);
      };
    },
    intercept: (target, currentHref) => {
      const block = current();
      if (block === undefined || pathOf(target, currentHref) === pathOf(currentHref, currentHref)) return false;
      block.onBlocked();
      return true;
    },
    restoreTarget: (currentHref) => {
      const block = current();
      if (block === undefined || pathOf(block.path, currentHref) === pathOf(currentHref, currentHref)) return null;
      block.onBlocked();
      return { path: block.path, state: block.state ?? null };
    },
  };
}

export const navigationGuard = createNavigationGuard();
