import { useDraftOrder, type DraftOrder, type Orderable } from '@/hooks/use-draft-order';
import { useSaveFailureReporter } from './save-failure';
import { useSettingsSaveSource } from './settings-save-scope';

export function useSavedOrder<T extends Orderable>(
  source: readonly T[],
  persist: (ordered: T[]) => Promise<unknown>,
  isMovable?: (item: T) => boolean,
): DraftOrder<T> {
  const order = useDraftOrder(source, isMovable);
  const reportFailure = useSaveFailureReporter();

  useSettingsSaveSource({
    isDirty: order.isDirty,
    isValid: true,
    save: async () => {
      try {
        await persist(order.items);
        order.clear();
        return true;
      } catch (error) {
        reportFailure(error);
        return false;
      }
    },
    reset: order.clear,
  });

  return order;
}
