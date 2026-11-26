import { Sprite, SpriteMaterial, type Texture } from 'three';
import { textureFrom } from './EntityTextures.ts';
import type { BitmapFont } from '../../assets/AssetLibrary.ts';

const SCALE = 0.025;
const PADDING = 1;
const OVERSAMPLE = 4;
const MAX_CHARACTERS = 64;

export class NameTag {
  readonly sprite: Sprite;
  private readonly material: SpriteMaterial;
  private readonly texture: Texture;
  readonly text: string;

  constructor(font: BitmapFont, label: string) {
    const text = Array.from(label).slice(0, MAX_CHARACTERS).join('');
    this.text = label;
    const width = font.measure(text) + PADDING * 2;
    const height = 9 + PADDING;
    const canvas = new OffscreenCanvas(width * OVERSAMPLE, height * OVERSAMPLE);
    const context = canvas.getContext('2d');
    if (context !== null) {
      context.imageSmoothingEnabled = false;
      context.scale(OVERSAMPLE, OVERSAMPLE);
      context.fillStyle = 'rgba(0, 0, 0, 0.25)';
      context.fillRect(0, 0, width, height);
      font.draw(context, text, PADDING, PADDING, { color: 0xffffff, shadow: false });
    }
    this.texture = textureFrom(canvas);
    this.texture.flipY = true;
    this.material = new SpriteMaterial({ map: this.texture, transparent: true, depthWrite: false });
    this.sprite = new Sprite(this.material);
    this.sprite.scale.set(width * SCALE, height * SCALE, 1);
    this.sprite.renderOrder = 10;
  }

  dispose(): void {
    this.material.dispose();
    this.texture.dispose();
  }
}
