export const PUBLIC_MESSAGE_SENDERS = ['player', 'staff', 'system'] as const;

export type PublicMessageSender = (typeof PUBLIC_MESSAGE_SENDERS)[number];
