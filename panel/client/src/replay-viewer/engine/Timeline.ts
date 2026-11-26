export interface TimelineSeries<T> {
  readonly times: Float64Array<ArrayBuffer>;
  readonly values: readonly T[];
}

export function searchTimes(times: ArrayLike<number>, time: number): number {
  let low = 0;
  let high = times.length - 1;
  let found = -1;
  while (low <= high) {
    const middle = (low + high) >> 1;
    if ((times[middle] ?? 0) <= time) {
      found = middle;
      low = middle + 1;
    } else {
      high = middle - 1;
    }
  }
  return found;
}

export class Timeline<T> {
  private readonly times: Float64Array;
  private readonly values: readonly T[];

  constructor(series: TimelineSeries<T>) {
    this.times = series.times;
    this.values = series.values;
  }

  get size(): number {
    return this.times.length;
  }

  last(): T | undefined {
    return this.values.at(-1);
  }

  indexAt(time: number): number {
    return searchTimes(this.times, time);
  }

  at(time: number): T | undefined {
    const index = this.indexAt(time);
    return index < 0 ? undefined : this.values[index];
  }

  timeAt(index: number): number | undefined {
    return this.times[index];
  }

  valueAt(index: number): T | undefined {
    return this.values[index];
  }
}

export class TimelineBuilder<T> {
  private readonly times: number[] = [];
  private readonly values: T[] = [];

  push(time: number, value: T): void {
    const last = this.times.length - 1;
    if (last >= 0 && this.times[last] === time) {
      this.values[last] = value;
      return;
    }
    this.times.push(time);
    this.values.push(value);
  }

  last(): T | undefined {
    return this.values.at(-1);
  }

  get size(): number {
    return this.times.length;
  }

  build(): TimelineSeries<T> {
    return { times: Float64Array.from(this.times), values: this.values };
  }
}
