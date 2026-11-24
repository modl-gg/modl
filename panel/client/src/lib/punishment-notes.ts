interface NoteText {
  readonly text: string;
}

export function withoutReasonCopy<N extends NoteText>(notes: readonly N[], reason: string | undefined): N[] {
  const [first, ...rest] = notes;
  if (first === undefined || reason === undefined || reason === '' || first.text !== reason) return [...notes];
  return rest;
}
