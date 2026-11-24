import test from 'node:test';
import assert from 'node:assert/strict';

import { moveFieldInSection, moveFieldToSection, moveSection, removeField, removeSection, sectionFields, sortedSections, upsertField } from './form-layout.ts';

const FORM = {
  sections: [{ id: 's1', order: 0 }, { id: 's2', order: 1 }],
  fields: [
    { id: 'a', order: 0, sectionId: 's1' },
    { id: 'b', order: 1, sectionId: 's1' },
    { id: 'c', order: 2, sectionId: 's1' },
    { id: 'x', order: 3, sectionId: 's2' },
    { id: 'y', order: 4, sectionId: 's2' },
  ],
};

const idsIn = (form: typeof FORM, sectionId: string) => sectionFields(form, sectionId).map((field) => field.id);

await test('moving a field within a section shifts the fields between, also across several rows', () => {
  assert.deepEqual(idsIn(moveFieldInSection(FORM, 0, 2, 's1'), 's1'), ['b', 'c', 'a']);
  assert.deepEqual(idsIn(moveFieldInSection(FORM, 2, 0, 's1'), 's1'), ['c', 'a', 'b']);
  assert.deepEqual(idsIn(moveFieldInSection(FORM, 0, 1, 's2'), 's2'), ['y', 'x']);
});

await test('moving a field to another section inserts it at the target index and renumbers both sections', () => {
  const moved = moveFieldToSection(FORM, 'b', 's2', 1);
  assert.deepEqual(idsIn(moved, 's1'), ['a', 'c']);
  assert.deepEqual(idsIn(moved, 's2'), ['x', 'b', 'y']);
  assert.deepEqual(idsIn(moveFieldToSection(FORM, 'a', 's2'), 's2'), ['x', 'y', 'a']);
  assert.equal(moveFieldToSection(FORM, 'a', 's1', 2), FORM);
});

await test('removing a field keeps the order of earlier moves in every section', () => {
  const reordered = moveFieldInSection(moveFieldInSection(FORM, 0, 2, 's1'), 0, 1, 's2');
  const removed = removeField(reordered, 'c');
  assert.deepEqual(idsIn(removed, 's1'), ['b', 'a']);
  assert.deepEqual(idsIn(removed, 's2'), ['y', 'x']);
  assert.deepEqual(sectionFields(removed, 's1').map((field) => field.order), [0, 1]);
  assert.equal(removeField(FORM, 'missing'), FORM);
});

await test('a new field, or one moved to another section, goes to the end of its section', () => {
  const reordered = moveFieldInSection(FORM, 0, 1, 's2');
  assert.deepEqual(idsIn(upsertField(reordered, { id: 'n', order: 0, sectionId: 's2' }), 's2'), ['y', 'x', 'n']);
  assert.deepEqual(idsIn(upsertField(reordered, { id: 'a', order: 0, sectionId: 's2' }), 's2'), ['y', 'x', 'a']);
  assert.deepEqual(idsIn(upsertField(reordered, { id: 'y', order: 0, sectionId: 's2' }), 's2'), ['y', 'x']);
});

await test('removing a section keeps the order of the others even when they are stored out of order', () => {
  const form = {
    fields: [{ id: 'a', order: 0, sectionId: 's2' }, { id: 'b', order: 0, sectionId: 's3' }],
    sections: [{ id: 's3', order: 2 }, { id: 's1', order: 0 }, { id: 's2', order: 1 }],
  };
  const removed = removeSection(form, 's1');
  assert.deepEqual(sortedSections(removed), [{ id: 's2', order: 0 }, { id: 's3', order: 1 }]);
  assert.deepEqual(removed.fields.map((field) => field.id), ['a', 'b']);
  const reordered = removeSection(moveSection(form, 2, 0), 's2');
  assert.deepEqual(sortedSections(reordered).map((section) => section.id), ['s3', 's1']);
});
