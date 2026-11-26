import type { CDPSession, Page } from "playwright-core";

const AUTHENTICATOR = {
  protocol: "ctap2",
  transport: "internal",
  hasResidentKey: true,
  hasUserVerification: true,
  isUserVerified: true,
  automaticPresenceSimulation: true,
} as const;

async function readCredentials(session: CDPSession, authenticatorId: string) {
  return (await session.send("WebAuthn.getCredentials", { authenticatorId })).credentials;
}

type Credential = Awaited<ReturnType<typeof readCredentials>>[number];

interface Attachment {
  readonly session: CDPSession;
  readonly authenticatorId: string;
}

async function attach(page: Page): Promise<Attachment> {
  const session = await page.context().newCDPSession(page);
  await session.send("WebAuthn.enable", { enableUI: false });
  const { authenticatorId } = await session.send("WebAuthn.addVirtualAuthenticator", { options: AUTHENTICATOR });
  return { session, authenticatorId };
}

export class VirtualAuthenticator {
  private attachment: Attachment;
  private remembered: readonly Credential[] = [];

  private constructor(attachment: Attachment) {
    this.attachment = attachment;
  }

  static async attach(page: Page): Promise<VirtualAuthenticator> {
    return new VirtualAuthenticator(await attach(page));
  }

  async remember(): Promise<void> {
    this.remembered = await readCredentials(this.attachment.session, this.attachment.authenticatorId);
  }

  async restore(page: Page): Promise<void> {
    if (await this.stillHoldsCredentials()) return;
    const attachment = await attach(page);
    for (const credential of this.remembered) await attachment.session.send("WebAuthn.addCredential", { authenticatorId: attachment.authenticatorId, credential });
    this.attachment = attachment;
  }

  private async stillHoldsCredentials(): Promise<boolean> {
    try {
      const held = new Set((await readCredentials(this.attachment.session, this.attachment.authenticatorId)).map((credential) => credential.credentialId));
      return this.remembered.every((credential) => held.has(credential.credentialId));
    } catch {
      return false;
    }
  }
}
