import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { useContinueReady } from '@/hooks/use-before-continue';
import { lockedStepChoices, stepChoiceOf, stepContinueReady, type SetupStepId } from '@/lib/setup-steps';
import { useRememberedChoice } from '../setup-choices';
import { SETUP_TITLE_ID } from '../setup-title';

export interface StepOption<T extends string> {
  value: T;
  labelKey: string;
}

interface StepChoiceMemory<T extends string> {
  step: SetupStepId;
  options: readonly StepOption<T>[];
  saved: T | null;
  done: boolean;
  none: T;
  configured?: boolean;
}

export interface StepChoiceState<T extends string> {
  value: T | null;
  locked: readonly T[];
  onChange: (value: T) => void;
}

export function useStepChoice<T extends string>({ step, options, saved, done, none, configured = saved !== null }: StepChoiceMemory<T>): StepChoiceState<T> {
  const [remembered, remember] = useRememberedChoice(step);
  const value = stepChoiceOf({ remembered, options: options.map((option) => option.value), saved, done, none });
  useContinueReady(stepContinueReady({ value, configured, done, none }));
  return { value, locked: lockedStepChoices({ saved, none }), onChange: remember };
}

interface StepChoiceProps<T extends string> extends StepChoiceState<T> {
  options: readonly StepOption<T>[];
}

export function StepChoice<T extends string>({ options, value, locked, onChange }: StepChoiceProps<T>) {
  const { t } = useTranslation();
  const id = useId();

  const select = (selected: string) => {
    const option = options.find((candidate) => candidate.value === selected);
    if (option) onChange(option.value);
  };

  return (
    <RadioGroup value={value ?? ''} onValueChange={select} aria-labelledby={SETUP_TITLE_ID}>
      {options.map((option) => (
        <div key={option.value} className="flex items-center gap-2">
          <RadioGroupItem id={`${id}-${option.value}`} value={option.value} disabled={locked.includes(option.value)} />
          <Label htmlFor={`${id}-${option.value}`}>{t(option.labelKey)}</Label>
        </div>
      ))}
    </RadioGroup>
  );
}
