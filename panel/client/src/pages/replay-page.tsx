import { Redirect, useSearch } from 'wouter';
import { readSearchParam } from '@/hooks/use-search-param';
import { panelReplayPath } from '@/lib/replay-reference';
import { PANEL_HOME_PATH } from '@/lib/return-path';

export default function ReplayPage() {
  const replayId = readSearchParam(useSearch(), 'id');
  return <Redirect to={replayId === '' ? PANEL_HOME_PATH : panelReplayPath(replayId)} replace />;
}
