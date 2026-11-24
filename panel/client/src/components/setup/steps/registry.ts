import type { ComponentType } from 'react';
import { SETUP_STEP_IDS, type SetupStepId } from '@/lib/setup-steps';
import { AccountStep } from './AccountStep';
import { AddressStep } from './AddressStep';
import { AiStep } from './AiStep';
import { ContentStep } from './ContentStep';
import { EmailStep } from './EmailStep';
import { IdentityStep } from './IdentityStep';
import { ImportStep } from './import/ImportStep';
import { MinecraftAccountStep } from './MinecraftAccountStep';
import { PlayerAddressesStep } from './PlayerAddressesStep';
import { ServersStep } from './ServersStep';
import { StaffStep } from './StaffStep';
import type { SetupStepProps } from './step-props';

interface SetupStepView {
  titleKey: string;
  introKey: string;
  component: ComponentType<SetupStepProps>;
}

interface SetupStepDefinition extends SetupStepView {
  step: SetupStepId;
}

const STEP_VIEWS = {
  account: { titleKey: 'pages.setup.account.title', introKey: 'pages.setup.account.intro', component: AccountStep },
  import: { titleKey: 'pages.setup.import.title', introKey: 'pages.setup.import.intro', component: ImportStep },
  identity: { titleKey: 'pages.setup.identity.title', introKey: 'pages.setup.identity.intro', component: IdentityStep },
  address: { titleKey: 'pages.setup.address.title', introKey: 'pages.setup.address.intro', component: AddressStep },
  email: { titleKey: 'pages.setup.email.title', introKey: 'pages.setup.email.intro', component: EmailStep },
  servers: { titleKey: 'pages.setup.servers.title', introKey: 'pages.setup.servers.intro', component: ServersStep },
  'minecraft-account': { titleKey: 'pages.setup.minecraftAccount.title', introKey: 'pages.setup.minecraftAccount.intro', component: MinecraftAccountStep },
  staff: { titleKey: 'pages.setup.staff.title', introKey: 'pages.setup.staff.intro', component: StaffStep },
  'player-addresses': { titleKey: 'pages.setup.playerAddresses.title', introKey: 'pages.setup.playerAddresses.intro', component: PlayerAddressesStep },
  ai: { titleKey: 'pages.setup.ai.title', introKey: 'pages.setup.ai.intro', component: AiStep },
  content: { titleKey: 'pages.setup.content.title', introKey: 'pages.setup.content.intro', component: ContentStep },
} as const satisfies Record<SetupStepId, SetupStepView>;

export const SETUP_STEP_REGISTRY: readonly SetupStepDefinition[] = SETUP_STEP_IDS.map((step) => ({ step, ...STEP_VIEWS[step] }));

export function setupStepDefinition(step: SetupStepId): SetupStepDefinition {
  return { step, ...STEP_VIEWS[step] };
}
