import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';

export function useInvalidatingMutation<TInput>(queryKey: QueryKey, send: (input: TInput) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: TInput) => {
      await send(input);
    },
    onSuccess: async () => queryClient.invalidateQueries({ queryKey }),
  });
}
