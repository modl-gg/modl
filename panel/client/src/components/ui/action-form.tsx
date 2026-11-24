import { createContext, use, type ReactNode } from 'react';

export interface FormSubmit {
  submit: () => void;
  canSubmit: boolean;
}

export const PageSubmitContext = createContext<FormSubmit | null>(null);

interface ActionFormProps {
  action: FormSubmit | null;
  className?: string | undefined;
  children: ReactNode;
}

export function ActionForm({ action, className, children }: ActionFormProps) {
  return (
    <form
      noValidate
      className={className}
      onSubmit={(event) => {
        if (event.target !== event.currentTarget) return;
        event.preventDefault();
        if (action?.canSubmit === true) action.submit();
      }}
    >
      <button type="submit" className="sr-only" tabIndex={-1} aria-hidden="true" disabled={action?.canSubmit !== true} />
      {children}
    </form>
  );
}

export function PageForm({ className, children }: Omit<ActionFormProps, 'action'>) {
  const pageSubmit = use(PageSubmitContext);
  return <ActionForm action={pageSubmit} className={className}>{children}</ActionForm>;
}
