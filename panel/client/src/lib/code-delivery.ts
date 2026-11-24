export function codeDeliveryHintKey(emailDelivery: boolean): string {
  return emailDelivery ? 'auth.verificationCodeDesc' : 'auth.verificationCodeConsoleDesc';
}
