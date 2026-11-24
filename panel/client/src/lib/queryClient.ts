import { QueryClient } from "@tanstack/react-query";
import { isRetryableHttpError } from "./http-error";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchInterval: false,
      refetchOnWindowFocus: false,
      refetchOnReconnect: true,
      retryOnMount: false,
      staleTime: 30_000,
      retry: (failureCount, error) => failureCount < 1 && isRetryableHttpError(error),
    },
    mutations: {
      retry: false,
    },
  },
});
