export async function loadImage(src: string, crossOrigin?: 'anonymous'): Promise<HTMLImageElement> {
  const image = new Image();
  if (crossOrigin) {
    image.crossOrigin = crossOrigin;
  }
  await new Promise<void>((resolve, reject) => {
    image.onload = () => { resolve(); };
    image.onerror = () => { reject(new Error(`Image failed to load: ${src}`)); };
    image.src = src;
  });
  return image;
}

export async function loadImageFromBytes(bytes: Uint8Array<ArrayBuffer>, type: string): Promise<HTMLImageElement> {
  const objectUrl = URL.createObjectURL(new Blob([bytes], { type }));
  try {
    return await loadImage(objectUrl);
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

export function create2dContext(width: number, height: number): CanvasRenderingContext2D {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) {
    throw new Error('2D canvas rendering is not available');
  }
  return context;
}
