import test from 'node:test';
import assert from 'node:assert/strict';

import { isEmailAuthSelected, resolveAppealEmailPolicy, resolveTicketEmailPolicy } from './ticket-email-policy.ts';

await test('with email delivery the form settings decide every email option', () => {
  const policy = resolveTicketEmailPolicy({ allowEmailNotifications: true }, true);
  assert.deepEqual(policy, { showEmailField: true, emailRequired: false, emailAuthForced: false, emailAuthOptional: true });
  assert.equal(isEmailAuthSelected(policy, true), true);
  assert.equal(isEmailAuthSelected(policy, false), false);

  const forced = resolveTicketEmailPolicy({ requireEmail: true, requireEmailAuth: true }, true);
  assert.equal(forced.emailAuthForced, true);
  assert.equal(forced.emailAuthOptional, false);
  assert.equal(isEmailAuthSelected(forced, false), true);
});

await test('without email delivery email is never an access factor', () => {
  const policy = resolveTicketEmailPolicy({ allowEmailNotifications: true, requireEmailAuth: true }, false);
  assert.deepEqual(policy, { showEmailField: false, emailRequired: false, emailAuthForced: false, emailAuthOptional: false });
  assert.equal(isEmailAuthSelected(policy, true), false);
});

await test('without email delivery a required contact email is still collected', () => {
  const policy = resolveTicketEmailPolicy({ requireEmail: true, requireEmailAuth: true }, false);
  assert.equal(policy.showEmailField, true);
  assert.equal(policy.emailRequired, true);
  assert.equal(isEmailAuthSelected(policy, true), false);
});

await test('appeals require an email only when the server can send email', () => {
  const withDelivery = resolveAppealEmailPolicy(true);
  assert.equal(withDelivery.showEmailField, true);
  assert.equal(withDelivery.emailRequired, true);

  const withoutDelivery = resolveAppealEmailPolicy(false);
  assert.equal(withoutDelivery.showEmailField, false);
  assert.equal(withoutDelivery.emailRequired, false);
});
