import { movedItem, renumbered } from '../../lib/move-item.ts';

export const UNSECTIONED = '';

export interface LayoutField {
  id: string;
  order: number;
  sectionId?: string | undefined;
}

export interface LayoutSection {
  id: string;
  order: number;
}

export interface FormLayout<Field extends LayoutField, Section extends LayoutSection> {
  fields: Field[];
  sections: Section[];
}

function byOrder(left: { order: number }, right: { order: number }): number {
  return left.order - right.order;
}

function sectionOf(field: LayoutField): string {
  return field.sectionId ?? UNSECTIONED;
}

export function isInSection(field: LayoutField, sectionId: string): boolean {
  return sectionOf(field) === sectionId;
}

export function sortedSections<Section extends LayoutSection>(form: FormLayout<LayoutField, Section>): Section[] {
  return [...form.sections].sort(byOrder);
}

export function sectionFields<Field extends LayoutField>(form: FormLayout<Field, LayoutSection>, sectionId: string): Field[] {
  return form.fields.filter((field) => isInSection(field, sectionId)).sort(byOrder);
}

export function moveSection<Field extends LayoutField, Section extends LayoutSection, Form extends FormLayout<Field, Section>>(
  form: Form & FormLayout<Field, Section>,
  dragIndex: number,
  hoverIndex: number,
): Form {
  return { ...form, sections: renumbered(movedItem(sortedSections(form), dragIndex, hoverIndex)) };
}

function withSectionFields<Field extends LayoutField, Form extends FormLayout<Field, LayoutSection>>(form: Form, placed: readonly Field[]): Form {
  const placedById = new Map(placed.map((field) => [field.id, field]));
  return { ...form, fields: form.fields.map((field) => placedById.get(field.id) ?? field) };
}

export function moveFieldInSection<Field extends LayoutField, Section extends LayoutSection, Form extends FormLayout<Field, Section>>(
  form: Form & FormLayout<Field, Section>,
  dragIndex: number,
  hoverIndex: number,
  sectionId: string,
): Form {
  return withSectionFields(form, renumbered(movedItem(sectionFields(form, sectionId), dragIndex, hoverIndex)));
}

export function moveFieldToSection<Field extends LayoutField, Section extends LayoutSection, Form extends FormLayout<Field, Section>>(
  form: Form & FormLayout<Field, Section>,
  fieldId: string,
  toSectionId: string,
  targetIndex?: number,
): Form {
  const moved = form.fields.find((field) => field.id === fieldId);
  if (moved === undefined || isInSection(moved, toSectionId)) return form;
  const source = sectionFields(form, sectionOf(moved)).filter((field) => field.id !== fieldId);
  const target = sectionFields(form, toSectionId);
  const insertAt = Math.min(targetIndex ?? target.length, target.length);
  const placedTarget = [...target.slice(0, insertAt), { ...moved, sectionId: toSectionId }, ...target.slice(insertAt)];
  return withSectionFields(form, [...renumbered(source), ...renumbered(placedTarget)]);
}

export function removeField<Field extends LayoutField, Section extends LayoutSection, Form extends FormLayout<Field, Section>>(
  form: Form & FormLayout<Field, Section>,
  fieldId: string,
): Form {
  const removed = form.fields.find((field) => field.id === fieldId);
  if (removed === undefined) return form;
  const remaining = { ...form, fields: form.fields.filter((field) => field.id !== fieldId) };
  return withSectionFields(remaining, renumbered(sectionFields(remaining, sectionOf(removed))));
}

function endOfSection(form: FormLayout<LayoutField, LayoutSection>, sectionId: string): number {
  return sectionFields(form, sectionId).reduce((last, field) => Math.max(last, field.order + 1), 0);
}

export function removeSection<Field extends LayoutField, Section extends LayoutSection, Form extends FormLayout<Field, Section>>(
  form: Form & FormLayout<Field, Section>,
  sectionId: string,
): Form {
  return {
    ...form,
    fields: form.fields.filter((field) => field.sectionId !== sectionId),
    sections: renumbered(sortedSections(form).filter((section) => section.id !== sectionId)),
  };
}

export function upsertField<Field extends LayoutField, Section extends LayoutSection, Form extends FormLayout<Field, Section>>(
  form: Form & FormLayout<Field, Section>,
  field: Field,
): Form {
  const existing = form.fields.find((candidate) => candidate.id === field.id);
  const placed = existing !== undefined && isInSection(existing, sectionOf(field)) ? field : { ...field, order: endOfSection(form, sectionOf(field)) };
  return {
    ...form,
    fields: existing === undefined
      ? [...form.fields, placed]
      : form.fields.map((candidate) => (candidate.id === field.id ? placed : candidate)),
  };
}

export function upsertSection<Field extends LayoutField, Section extends LayoutSection, Form extends FormLayout<Field, Section>>(
  form: Form & FormLayout<Field, Section>,
  section: Section,
): Form {
  const exists = form.sections.some((candidate) => candidate.id === section.id);
  return {
    ...form,
    sections: exists
      ? form.sections.map((candidate) => (candidate.id === section.id ? section : candidate))
      : [...form.sections, { ...section, order: form.sections.length }],
  };
}
