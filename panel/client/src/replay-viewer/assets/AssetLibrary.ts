export type AssetLoadFailureKind = 'network' | 'unsupported_version' | 'aborted' | 'corrupt';

export class AssetLoadError extends Error {
  readonly kind: AssetLoadFailureKind;

  constructor(kind: AssetLoadFailureKind, message: string) {
    super(message);
    this.name = 'AssetLoadError';
    this.kind = kind;
  }
}

export interface NineSliceBorder {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
}

export interface GuiSprite {
  readonly image: ImageBitmap;
  readonly width: number;
  readonly height: number;
  readonly nineSlice: NineSliceBorder | null;
}

export interface TextStyle {
  readonly color: number;
  readonly shadow: boolean;
  readonly bold?: boolean;
  readonly italic?: boolean;
}

export type DrawingContext = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;

export interface BitmapFont {
  readonly lineHeight: number;
  measure(text: string, bold?: boolean): number;
  draw(context: DrawingContext, text: string, x: number, y: number, style: TextStyle): number;
}

export interface AssetLibrary {
  readonly version: string;
  texture(path: string): ImageBitmap | null;
  guiSprite(id: string): GuiSprite | null;
  font(): BitmapFont;
  lang(key: string): string | undefined;
  skin(textureHash: string): Promise<ImageBitmap>;
  itemIcon(itemKey: string): ImageBitmap | null;
  dispose(): void;
}

export type AssetProgressListener = (loadedBytes: number, totalBytes: number) => void;
