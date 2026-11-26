export function hannWindow(size: number): Float64Array {
  const window = new Float64Array(size);
  for (let index = 0; index < size; index += 1) window[index] = 0.5 - 0.5 * Math.cos((2 * Math.PI * index) / (size - 1));
  return window;
}

function bitReversal(size: number): Uint32Array {
  const bits = Math.log2(size);
  if (!Number.isInteger(bits)) throw new Error(`an FFT size must be a power of two, not ${String(size)}`);
  const table = new Uint32Array(size);
  for (let index = 0; index < size; index += 1) {
    let reversed = 0;
    for (let bit = 0; bit < bits; bit += 1) reversed |= ((index >> bit) & 1) << (bits - 1 - bit);
    table[index] = reversed;
  }
  return table;
}

export class Spectrum {
  readonly size: number;
  private readonly window: Float64Array;
  private readonly reversal: Uint32Array;
  private readonly real: Float64Array;
  private readonly imaginary: Float64Array;

  constructor(size: number) {
    this.size = size;
    this.window = hannWindow(size);
    this.reversal = bitReversal(size);
    this.real = new Float64Array(size);
    this.imaginary = new Float64Array(size);
  }

  magnitudes(samples: Float32Array, start: number, into: Float64Array): void {
    const { size, real, imaginary, reversal, window } = this;
    for (let index = 0; index < size; index += 1) {
      const target = reversal[index] ?? 0;
      real[target] = (samples[start + index] ?? 0) * (window[index] ?? 0);
      imaginary[target] = 0;
    }
    for (let span = 2; span <= size; span *= 2) {
      const half = span / 2;
      const step = (-2 * Math.PI) / span;
      for (let offset = 0; offset < size; offset += span) {
        for (let index = 0; index < half; index += 1) {
          const cos = Math.cos(step * index);
          const sin = Math.sin(step * index);
          const even = offset + index;
          const odd = even + half;
          const oddReal = real[odd] ?? 0;
          const oddImaginary = imaginary[odd] ?? 0;
          const turnedReal = oddReal * cos - oddImaginary * sin;
          const turnedImaginary = oddReal * sin + oddImaginary * cos;
          const evenReal = real[even] ?? 0;
          const evenImaginary = imaginary[even] ?? 0;
          real[even] = evenReal + turnedReal;
          imaginary[even] = evenImaginary + turnedImaginary;
          real[odd] = evenReal - turnedReal;
          imaginary[odd] = evenImaginary - turnedImaginary;
        }
      }
    }
    for (let bin = 0; bin < into.length; bin += 1) into[bin] = Math.hypot(real[bin] ?? 0, imaginary[bin] ?? 0);
  }
}
