export interface TicketEmailFormSettings {
  requireEmail?: boolean;
  allowEmailNotifications?: boolean;
  requireEmailAuth?: boolean;
}

export interface TicketEmailPolicy {
  showEmailField: boolean;
  emailRequired: boolean;
  emailAuthForced: boolean;
  emailAuthOptional: boolean;
}

export function resolveTicketEmailPolicy(form: TicketEmailFormSettings, emailDelivery: boolean): TicketEmailPolicy {
  const emailRequired = form.requireEmail === true;
  if (!emailDelivery) {
    return { showEmailField: emailRequired, emailRequired, emailAuthForced: false, emailAuthOptional: false };
  }

  const showEmailField = emailRequired || form.allowEmailNotifications !== false;
  const emailAuthForced = form.requireEmailAuth === true;
  return {
    showEmailField,
    emailRequired,
    emailAuthForced,
    emailAuthOptional: showEmailField && !emailAuthForced,
  };
}

export function isEmailAuthSelected(policy: TicketEmailPolicy, optedIn: boolean): boolean {
  return policy.emailAuthForced || (policy.emailAuthOptional && optedIn);
}

export function resolveAppealEmailPolicy(emailDelivery: boolean): TicketEmailPolicy {
  return resolveTicketEmailPolicy({ requireEmail: emailDelivery, allowEmailNotifications: true }, emailDelivery);
}
