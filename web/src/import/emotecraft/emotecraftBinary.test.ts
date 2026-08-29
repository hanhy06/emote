import { describe, expect, it } from "vitest";
import { emotecraftAdapter } from "./emotecraftAdapter";
import { decodeLatestEmotecraft, probeLatestEmotecraft } from "./emotecraftBinary";

describe("latest Emotecraft binary", () => {
  it("decodes and imports the current v8 container and compact v6 animation", async () => {
    const bytes = file([
      packet(0x99, 6, animation()),
      packet(0x11, 2, header()),
      packet(0x12, 0x12, concat(i32(3), [1, 2, 3])),
      packet(3, 2, [4, 5, 6]),
    ]);

    expect(probeLatestEmotecraft(bytes)).toBe(true);
    expect(decodeLatestEmotecraft(bytes)).toMatchObject({
      metadata: { name: "Wave", description: "Description", author: "Author", folderPath: "Folder", badges: ["Badge"] },
      icon: new Uint8Array([1, 2, 3]),
      song: new Uint8Array([4, 5, 6]),
      animation: {
        uuid: "00112233-4455-6677-8899-aabbccddeeff",
        lengthTicks: 20,
        loop: "loop_from_tick",
        loopStartTick: 5,
        format: "geckolib",
        beginTick: 2,
        endTick: 18,
        pivots: { hip: [0, 12, 0] },
        parents: { torso: "hip" },
      },
    });
    const rotation = decodeLatestEmotecraft(bytes).animation.bones.head.rotation[0];
    expect(rotation).toEqual([
      { startTick: 0, endTick: 1, start: 10, end: 10, easing: "linear", easingArgs: [] },
      { startTick: 1, endTick: 3, start: 10, end: "((q.anim_time)*(20))", easing: "easeinquad", easingArgs: [] },
    ]);
    expect(emotecraftAdapter.probe({ name: "wave.emotecraft", bytes })).toMatchObject({ confidence: 100 });
    const imported = await emotecraftAdapter.import({ name: "wave.emotecraft", bytes });
    expect(imported.source).toBe("emotecraft_binary");
    expect(imported.animations[0].durationTicks).toBe(20);
  });

  it("rejects legacy animation versions and malformed packet boundaries", () => {
    expect(probeLatestEmotecraft(file([packet(0x99, 5, [])]))).toBe(false);
    expect(() => decodeLatestEmotecraft(file([packet(0x99, 5, [])]))).toThrow("version must be 6");
    const malformed = file([packet(0x99, 6, animation())]);
    malformed[8] = 0x7f;
    expect(probeLatestEmotecraft(malformed)).toBe(false);
    expect(() => decodeLatestEmotecraft(malformed)).toThrow("sub-packet size");
  });
});

function animation(): number[] {
  const flags = 1 | 16 | 32 | 64;
  const head = concat(
    varint(1), // rotation X only
    varint(2),
    varint(0 | 1 | 8), f32(10), // constant, one tick
    varint((12 << 4) | 0), expressions([binary(8, identifier("q.anim_time"), floating(20))]), f32(2),
  );
  return concat(
    f32(0),
    varint(flags), f32(20), f32(5), f32(2), f32(18),
    hex("00112233445566778899aabbccddeeff"),
    map([[protocol("head"), head]]),
    varint(0), varint(0), varint(0),
    map([[protocol("hip"), concat(f32(0), f32(12), f32(0))]]),
    map([[protocol("torso"), protocol("hip")]]),
  );
}

function header(): number[] {
  return concat(legacy("Wave"), legacy("Description"), legacy("Author"), legacy("Folder"), varint(1), legacy("Badge"));
}

function file(packets: number[][]): Uint8Array {
  return new Uint8Array(concat(i32(8), [0x10, packets.length], ...packets));
}

function packet(id: number, version: number, payload: number[]): number[] {
  return concat([id, version], i32(payload.length), payload);
}

function expressions(values: number[][]): number[] {
  return concat(varint(values.length), ...values);
}

function binary(operator: number, left: number[], right: number[]): number[] {
  return concat([8, operator], left, right);
}

function identifier(value: string): number[] {
  const [object, property] = value.split(".");
  return concat([10, 4], protocol(object), protocol(property));
}

function floating(value: number): number[] {
  return concat([5], f32(value));
}

function map(entries: [number[], number[]][]): number[] {
  return concat(varint(entries.length), ...entries.flat());
}

function protocol(value: string): number[] {
  const bytes = [...new TextEncoder().encode(value)];
  return concat(varint(bytes.length), bytes);
}

function legacy(value: string): number[] {
  const bytes = [...new TextEncoder().encode(value)];
  return concat(i32(bytes.length), bytes);
}

function varint(value: number): number[] {
  const result: number[] = [];
  do {
    let byte = value & 0x7f;
    value >>>= 7;
    if (value) byte |= 0x80;
    result.push(byte);
  } while (value);
  return result;
}

function i32(value: number): number[] {
  const bytes = new Uint8Array(4);
  new DataView(bytes.buffer).setInt32(0, value, false);
  return [...bytes];
}

function f32(value: number): number[] {
  const bytes = new Uint8Array(4);
  new DataView(bytes.buffer).setFloat32(0, value, false);
  return [...bytes];
}

function hex(value: string): number[] {
  return value.match(/../g)!.map((byte) => Number.parseInt(byte, 16));
}

function concat(...parts: (number[] | Uint8Array)[]): number[] {
  return parts.flatMap((part) => [...part]);
}
