import React, { useState } from 'react';
import { ArrowUpRight, Loader2, AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { usePlayerWindow } from '@/contexts/PlayerWindowContext';
import { usePlayerLookup, extractPlayerIdentifier } from '@/hooks/use-player-lookup';
import { cn } from '@/lib/utils';

interface ClickablePlayerProps {
  children: React.ReactNode;
  playerText: string;
  uuid?: string | undefined;
  className?: string;
  showIcon?: boolean;
  variant?: 'text' | 'button';
  size?: 'sm' | 'md' | 'lg';
}

export function ClickablePlayer({ 
  children, 
  playerText, 
  uuid,
  className,
  showIcon = false,
  variant = 'text',
  size = 'md'
}: ClickablePlayerProps) {
  const [isLookingUp, setIsLookingUp] = useState(false);
  const { openPlayerWindow } = usePlayerWindow();
  
  const identifier = (uuid?.trim()) || extractPlayerIdentifier(playerText);
  
  const { data: playerData, error, refetch } = usePlayerLookup(identifier);

  const openLookedUpPlayer = async () => {
    const player = playerData ?? (await refetch()).data;
    if (player) {
      openPlayerWindow(player.uuid, player.username);
    }
  };

  const handleClick = (e: React.SyntheticEvent) => {
    e.preventDefault();
    e.stopPropagation();

    if (!identifier) return;

    const knownUuid = uuid?.trim();
    if (knownUuid) {
      openPlayerWindow(knownUuid, playerText);
      return;
    }

    setIsLookingUp(true);
    void openLookedUpPlayer().finally(() => {
      setIsLookingUp(false);
    });
  };

  if (variant === 'button') {
    return (
      <Button
        variant="ghost"
        size={size === 'sm' ? 'sm' : 'default'}
        className={cn('h-auto p-1 hover:bg-muted/50', className)}
        onClick={handleClick}
        disabled={!identifier || isLookingUp}
      >
        {children}
        {showIcon && (
          <span className="ml-1">
            {isLookingUp ? (
              <Loader2 className="h-3 w-3 animate-spin" />
            ) : error ? (
              <AlertCircle className="h-3 w-3 text-destructive-text" />
            ) : (
              <ArrowUpRight className="h-3 w-3" />
            )}
          </span>
        )}
      </Button>
    );
  }

  return (
    <span
      className={cn(
        'cursor-pointer hover:underline text-primary hover:text-primary/80',
        'inline-flex items-center gap-1',
        !identifier && 'cursor-default hover:no-underline text-muted-foreground',
        className
      )}
      onClick={handleClick}
      role={identifier ? 'button' : 'text'}
      tabIndex={identifier ? 0 : -1}
      onKeyDown={(e) => {
        if (identifier && (e.key === 'Enter' || e.key === ' ')) {
          e.preventDefault();
          handleClick(e);
        }
      }}
    >
      {children}
      {showIcon && identifier && (
        <span className="inline-flex items-center">
          {isLookingUp ? (
            <Loader2 className="h-3 w-3 animate-spin ml-1" />
          ) : error ? (
            <AlertCircle className="h-3 w-3 text-destructive-text ml-1" />
          ) : (
            <ArrowUpRight className="h-3 w-3 ml-1" />
          )}
        </span>
      )}
    </span>
  );
}
