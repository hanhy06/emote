import { convertIndexedToRgb, decode, encode } from "fast-png";
import type { SkinIR } from "./animationIR";

export function decodeSkinPng(base64: string): Uint8Array {
  const bytes = Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
  if (bytes.length > 1_048_576) throw new Error("Skin PNG must not exceed 1 MiB.");
  const image = decode(bytes);
  if (image.width !== 64 || image.height !== 64) throw new Error("Skin PNG must be 64×64 with wide UVs.");
  const samples = image.palette ? convertIndexedToRgb(image) : image.data;
  const channels = image.palette ? image.palette[0].length : image.channels;
  const depth = image.palette ? 8 : image.depth;
  const maximum = 2 ** depth - 1;
  const pixels = new Uint8Array(64 * 64 * 4);
  for (let pixel = 0; pixel < 64 * 64; pixel++) {
    const raw = (channel: number) => depth < 8
      ? (samples[(pixel * depth) >> 3] >> (8 - depth - (pixel * depth) % 8)) & maximum
      : samples[pixel * channels + channel];
    const sample = (channel: number) => Math.round(raw(channel) * 255 / maximum);
    const gray = channels < 3;
    pixels[pixel * 4] = sample(0);
    pixels[pixel * 4 + 1] = sample(gray ? 0 : 1);
    pixels[pixel * 4 + 2] = sample(gray ? 0 : 2);
    pixels[pixel * 4 + 3] = channels === 2 || channels === 4 ? sample(channels - 1) : 255;
    if (!image.palette && image.transparency?.length
      && [...image.transparency].every((value, channel) => raw(channel) === value)) pixels[pixel * 4 + 3] = 0;
  }
  return pixels;
}

export function encodeSkinPng(pixels: Uint8Array): string {
  const bytes = encode({ width: 64, height: 64, channels: 4, depth: 8, data: pixels });
  return btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(""));
}

function decodeMask(base64: string): Uint8Array {
  const pixels = decodeSkinPng(base64);
  for (let i = 0; i < pixels.length; i += 4) {
    if (!pixels[i + 3]) continue;
    const black = pixels[i] === 0 && pixels[i + 1] === 0 && pixels[i + 2] === 0;
    const white = pixels[i] === 255 && pixels[i + 1] === 255 && pixels[i + 2] === 255;
    if (!black && !white) throw new Error("Override mask must contain only black, white, or transparent pixels.");
  }
  return pixels;
}

export function createSkinOverride(texture: string, mask?: string): NonNullable<SkinIR["override"]> {
  const pixels = decodeSkinPng(texture);
  if (mask !== undefined) {
    decodeMask(mask);
    return { texture, mask };
  }
  const generated = new Uint8Array(pixels.length);
  for (let i = 0; i < pixels.length; i += 4) {
    const value = pixels[i + 3] > 0 ? 255 : 0;
    generated[i] = generated[i + 1] = generated[i + 2] = value;
    generated[i + 3] = 255;
  }
  return { texture, mask: encodeSkinPng(generated) };
}

export function composeSkin(skin: SkinIR): Uint8Array {
  const pixels = skin.default ? decodeSkinPng(skin.default) : new Uint8Array(64 * 64 * 4);
  const texture = skin.override ? decodeSkinPng(skin.override.texture) : undefined;
  const mask = skin.override ? decodeMask(skin.override.mask) : undefined;
  for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
    const offset = (y * 64 + x) * 4;
    const base = y < 16 && x < 32 || y >= 16 && y < 32 || y >= 48 && x >= 16 && x < 48;
    if (!skin.default && base) pixels.fill(160, offset, offset + 3);
    if (texture && mask && mask[offset + 3] > 0 && mask[offset] === 255) pixels.set(texture.subarray(offset, offset + 4), offset);
    if (base) pixels[offset + 3] = 255;
  }
  return pixels;
}
