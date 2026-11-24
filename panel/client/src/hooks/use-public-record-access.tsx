import { useLayoutEffect } from 'react';
import { useSearch } from 'wouter';
import { useQueryClient } from '@tanstack/react-query';
import { rememberPublicRecordGrant } from '@/lib/public-record-access';
import { publicTicketQueryKey } from '@/hooks/data/tickets';
import { readSearchParam, useSearchParamRemover } from '@/hooks/use-search-param';

const RECORD_ACCESS_KEY_PARAM = 'key';

export function usePublicRecordAccessKey(recordId: string | undefined): boolean {
  const search = useSearch();
  const queryClient = useQueryClient();
  const removeFromAddressBar = useSearchParamRemover(RECORD_ACCESS_KEY_PARAM);
  const pendingKey = readSearchParam(search, RECORD_ACCESS_KEY_PARAM);

  useLayoutEffect(() => {
    if (!recordId || !pendingKey) return;
    rememberPublicRecordGrant(recordId, pendingKey, 'access-key');
    queryClient.removeQueries({ queryKey: publicTicketQueryKey(recordId) });
    removeFromAddressBar();
  }, [recordId, pendingKey, queryClient, removeFromAddressBar]);

  return pendingKey === '';
}
