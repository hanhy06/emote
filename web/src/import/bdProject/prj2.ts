import type { ImportInput } from "../adapter";
import { cachedInputPromise } from "../inputCache";

const decoder = new TextDecoder();

export interface Prj2Archive {
  version: number;
  files: Map<string, Uint8Array>;
}

export function hasGzipHeader(bytes: Uint8Array): boolean {
  return bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
}

export async function readPrj2(input: ImportInput): Promise<Prj2Archive> {
  return cachedInputPromise(input, "bd_project_prj2", () => readPrj2Uncached(input));
}

async function readPrj2Uncached(input: ImportInput): Promise<Prj2Archive> {
  if (!hasGzipHeader(input.bytes)) throw new Error("BD project is not gzip-compressed.");
  const compressed = input.bytes.slice().buffer;
  const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream("gzip"));
  const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
  if (decoder.decode(bytes.subarray(0, 4)) !== "PRJ2") throw new Error("BD project does not contain a PRJ2 archive.");
  if (bytes.length < 9) throw new Error("BD project PRJ2 header is truncated.");

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const version = view.getUint8(4);
  const fileCount = view.getUint32(5, true);
  const files = new Map<string, Uint8Array>();
  let offset = 9;
  for (let index = 0; index < fileCount; index++) {
    requireBytes(bytes, offset, 2);
    const nameLength = view.getUint16(offset, true);
    offset += 2;
    requireBytes(bytes, offset, nameLength + 4);
    const name = decoder.decode(bytes.subarray(offset, offset + nameLength));
    offset += nameLength;
    const contentLength = view.getUint32(offset, true);
    offset += 4;
    requireBytes(bytes, offset, contentLength);
    if (files.has(name)) throw new Error(`BD project contains duplicate PRJ2 entry: ${name}`);
    files.set(name, bytes.slice(offset, offset + contentLength));
    offset += contentLength;
  }
  if (offset !== bytes.length) throw new Error("BD project contains trailing PRJ2 data.");
  return { version, files };
}

function requireBytes(bytes: Uint8Array, offset: number, length: number): void {
  if (offset < 0 || length < 0 || offset + length > bytes.length) throw new Error("BD project PRJ2 entry is truncated.");
}
