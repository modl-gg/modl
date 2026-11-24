import { skipToken, useQuery } from '@tanstack/react-query';
import { fetchReplayMetadata } from '@/lib/replay-api';

export const REPLAY_COMPLETE_STATUS = 'COMPLETE';

export function useReplayMetadata(replayId: string) {
  return useQuery({
    queryKey: ['/v1/public/replays', replayId],
    queryFn: replayId === '' ? skipToken : async () => fetchReplayMetadata(replayId),
    retry: false,
    refetchOnWindowFocus: false,
  });
}
