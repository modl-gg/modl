import { z } from 'zod';

const optionalText = z.string().optional().catch(undefined);

export const formSectionSchema = z.object({
  id: z.string(),
  title: z.string().catch(''),
  description: optionalText,
  order: z.number().catch(0),
  showIfFieldId: optionalText,
  showIfValue: optionalText,
  showIfValues: z.array(z.string()).optional().catch(undefined),
  hideByDefault: z.boolean().optional().catch(undefined),
});

export function formFieldSchema<const Type extends string>(types: Readonly<Record<Type, true>>) {
  const typeNames = Object.keys(types).filter((name): name is Type => name in types);
  return z.object({
    id: z.string(),
    type: z.enum(typeNames),
    label: z.string().catch(''),
    description: optionalText,
    required: z.boolean().catch(false),
    options: z.array(z.string()).optional().catch(undefined),
    order: z.number().catch(0),
    sectionId: optionalText,
    goToSection: optionalText,
    optionSectionMapping: z.record(z.string(), z.string()).optional().catch(undefined),
  });
}

export function validEntries<T>(schema: z.ZodType<T>, value: unknown): T[] {
  return (Array.isArray(value) ? value : []).flatMap((entry: unknown) => {
    const parsed = schema.safeParse(entry);
    return parsed.success ? [parsed.data] : [];
  });
}

export function byOrder<T extends { order: number }>(items: readonly T[]): T[] {
  return [...items].sort((a, b) => a.order - b.order);
}
