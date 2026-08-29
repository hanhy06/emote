export type PalExpression = number | string;

export interface EmotecraftFile {
  animation: PalAnimation;
  metadata: {
    name?: string;
    description?: string;
    author?: string;
    folderPath?: string;
    badges: string[];
  };
  icon?: Uint8Array;
  song?: Uint8Array;
}

export interface PalAnimation {
  uuid: string;
  lengthTicks: number;
  loop: "once" | "hold" | "loop_from_tick";
  loopStartTick: number;
  format: "geckolib" | "player_animator";
  applyBendToOtherBones: boolean;
  easeBeforeKeyframe: boolean;
  beginTick?: number;
  endTick?: number;
  bones: Record<string, PalBoneAnimation>;
  effects: {
    sounds: { tick: number; sound: string }[];
    particles: { tick: number; effect: string; locator: string; script: string }[];
    instructions: { tick: number; instruction: string }[];
  };
  pivots: Record<string, readonly [number, number, number]>;
  parents: Record<string, string>;
}

export interface PalBoneAnimation {
  rotation: PalAxisChannels;
  position: PalAxisChannels;
  scale: PalAxisChannels;
  bend: PalKeyframe[];
}

export type PalAxisChannels = readonly [PalKeyframe[], PalKeyframe[], PalKeyframe[]];

export interface PalKeyframe {
  startTick: number;
  endTick: number;
  start: PalExpression;
  end: PalExpression;
  easing: string;
  easingArgs: PalExpression[][];
}

const NETWORK_VERSION = 8;
const FILE_TASK = 0x10;
const ANIMATION_PACKET = 0x99;
const ANIMATION_VERSION = 6;
const LEGACY_ANIMATION_PACKET = 0;
const LEGACY_ANIMATION_VERSION = 1;
const HEADER_PACKET = 0x11;
const HEADER_VERSION = 2;
const ICON_PACKET = 0x12;
const ICON_VERSION = 0x12;
const SONG_PACKET = 3;
const SONG_VERSION = 2;
const MAX_COLLECTION_SIZE = 100_000;
const MAX_STRING_BYTES = 1_048_576;
const LEGACY_PLAYER_BONES = ["head", "body", "right_arm", "left_arm", "right_leg", "left_leg"] as const;
const LEGACY_POSITION_DEFAULTS: Readonly<Record<string, readonly [number, number, number]>> = {
  right_arm: [-5, 2, 0],
  left_arm: [5, 2, 0],
  left_leg: [1.9, 12, 0.1],
  right_leg: [-1.9, 12, 0.1],
};

const EASINGS: Readonly<Record<number, string>> = {
  0: "linear", 1: "constant", 6: "easeinsine", 7: "easeoutsine", 8: "easeinoutsine",
  9: "easeincubic", 10: "easeoutcubic", 11: "easeinoutcubic", 12: "easeinquad",
  13: "easeoutquad", 14: "easeinoutquad", 15: "easeinquart", 16: "easeoutquart",
  17: "easeinoutquart", 18: "easeinquint", 19: "easeoutquint", 20: "easeinoutquint",
  21: "easeinexpo", 22: "easeoutexpo", 23: "easeinoutexpo", 24: "easeincirc",
  25: "easeoutcirc", 26: "easeinoutcirc", 27: "easeinback", 28: "easeoutback",
  29: "easeinoutback", 30: "easeinelastic", 31: "easeoutelastic", 32: "easeinoutelastic",
  33: "easeinbounce", 34: "easeoutbounce", 35: "easeinoutbounce", 36: "catmullrom",
  37: "step", 38: "bezier",
};

export function probeLatestEmotecraft(bytes: Uint8Array): boolean {
  try {
    const reader = new BinaryReader(bytes);
    if (reader.readInt32() !== NETWORK_VERSION || reader.readUint8() !== FILE_TASK) return false;
    const count = reader.readUint8();
    let animation = false;
    for (let index = 0; index < count; index++) {
      const id = reader.readUint8();
      const version = reader.readUint8();
      const size = reader.readInt32();
      if (size < 0 || size > reader.remaining) return false;
      animation ||= isSupportedAnimationPacket(id, version);
      reader.skip(size);
    }
    return animation && reader.remaining === 0;
  } catch {
    return false;
  }
}

export function decodeLatestEmotecraft(bytes: Uint8Array): EmotecraftFile {
  const reader = new BinaryReader(bytes);
  const networkVersion = reader.readInt32();
  if (networkVersion !== NETWORK_VERSION) throw new Error(`Emotecraft network version must be ${NETWORK_VERSION}, received ${networkVersion}.`);
  const task = reader.readUint8();
  if (task !== FILE_TASK) throw new Error(`Emotecraft packet task must be FILE (0x10), received 0x${task.toString(16)}.`);

  const subPacketCount = reader.readUint8();
  let animation: PalAnimation | undefined;
  const metadata: EmotecraftFile["metadata"] = { badges: [] };
  let icon: Uint8Array | undefined;
  let song: Uint8Array | undefined;

  for (let index = 0; index < subPacketCount; index++) {
    const id = reader.readUint8();
    const version = reader.readUint8();
    const size = reader.readInt32();
    if (size < 0 || size > reader.remaining) throw new Error(`Invalid Emotecraft sub-packet size ${size}.`);
    const packet = reader.subReader(size);

    if (id === ANIMATION_PACKET) {
      if (version !== ANIMATION_VERSION) throw new Error(`Emotecraft animation binary version must be ${ANIMATION_VERSION}, received ${version}.`);
      if (animation) throw new Error("Emotecraft file contains more than one animation packet.");
      packet.readFloat32(); // Saved files start at tick zero; the animation data follows it.
      animation = readAnimationV6(packet);
    } else if (id === LEGACY_ANIMATION_PACKET) {
      if (version !== LEGACY_ANIMATION_VERSION) throw new Error(`Emotecraft legacy animation packet version must be ${LEGACY_ANIMATION_VERSION}, received ${version}.`);
      if (animation) throw new Error("Emotecraft file contains more than one animation packet.");
      packet.readInt32(); // Saved files start at tick zero; the animation data follows it.
      animation = readLegacyAnimationV1(packet);
    } else if (id === HEADER_PACKET) {
      if (version !== 1 && version !== HEADER_VERSION) throw new Error(`Emotecraft header version must be 1 or ${HEADER_VERSION}, received ${version}.`);
      metadata.name = emptyToUndefined(packet.readLegacyString());
      metadata.description = emptyToUndefined(packet.readLegacyString());
      metadata.author = emptyToUndefined(packet.readLegacyString());
      if (version >= 2) {
        metadata.folderPath = emptyToUndefined(packet.readLegacyString());
        metadata.badges = packet.readList(() => packet.readLegacyString());
      }
    } else if (id === ICON_PACKET) {
      if (version !== ICON_VERSION) throw new Error(`Emotecraft icon version must be ${ICON_VERSION}, received ${version}.`);
      const iconSize = packet.readInt32();
      if (iconSize < 0 || iconSize > packet.remaining) throw new Error(`Invalid Emotecraft icon size ${iconSize}.`);
      if (iconSize > 0) icon = packet.readBytes(iconSize);
    } else if (id === SONG_PACKET) {
      if (version !== SONG_VERSION) throw new Error(`Emotecraft song version must be ${SONG_VERSION}, received ${version}.`);
      song = packet.readBytes(packet.remaining);
    }
    packet.skip(packet.remaining);
  }

  if (reader.remaining !== 0) throw new Error(`Emotecraft file has ${reader.remaining} trailing bytes.`);
  if (!animation) throw new Error("Emotecraft file does not contain a supported animation packet.");
  return { animation, metadata, ...(icon ? { icon } : {}), ...(song ? { song } : {}) };
}

function isSupportedAnimationPacket(id: number, version: number): boolean {
  return (id === ANIMATION_PACKET && version === ANIMATION_VERSION)
    || (id === LEGACY_ANIMATION_PACKET && version === LEGACY_ANIMATION_VERSION);
}

function readLegacyAnimationV1(reader: BinaryReader): PalAnimation {
  const beginTick = reader.readInt32();
  const sourceEndTick = Math.max(reader.readInt32(), beginTick + 1);
  if (sourceEndTick <= 0) throw new Error("Emotecraft legacy end tick must be greater than zero.");
  const stopTick = reader.readInt32();
  const looped = reader.readUint8() !== 0;
  const encodedReturnTick = reader.readInt32();
  const returnTick = Math.max(0, encodedReturnTick - 1);
  if (looped && returnTick > sourceEndTick) throw new Error("Emotecraft legacy return tick exceeds the end tick.");
  const easeBefore = reader.readUint8() !== 0;
  reader.readUint8(); // Removed NSFW flag.
  const keyframeSize = reader.readUint8();
  if (keyframeSize < 9) throw new Error(`Invalid Emotecraft legacy keyframe size ${keyframeSize}.`);

  const bones: Record<string, PalBoneAnimation> = {};
  for (const name of LEGACY_PLAYER_BONES) {
    const bone = readLegacyPartV1(reader, name, keyframeSize, easeBefore);
    if (boneHasFrames(bone)) bones[name] = bone;
  }
  const bodyBend = bones.body?.bend ?? [];
  if (bodyBend.length > 0) {
    bones.torso = { ...(bones.torso ?? emptyBone()), bend: bodyBend };
    bones.body = { ...bones.body, bend: [] };
    if (!boneHasFrames(bones.body)) delete bones.body;
  }

  const lengthTicks = looped ? sourceEndTick : stopTick <= sourceEndTick ? sourceEndTick + 3 : stopTick;
  const hold = looped && returnTick >= sourceEndTick - 1;
  return {
    uuid: reader.readUuid(),
    lengthTicks,
    loop: !looped ? "once" : hold ? "hold" : "loop_from_tick",
    loopStartTick: looped && !hold ? returnTick : 0,
    format: "player_animator",
    applyBendToOtherBones: bodyBend.length > 0,
    easeBeforeKeyframe: easeBefore,
    beginTick,
    endTick: sourceEndTick,
    bones,
    effects: { sounds: [], particles: [], instructions: [] },
    pivots: {},
    parents: {},
  };
}

function readLegacyPartV1(reader: BinaryReader, name: string, keyframeSize: number, easeBefore: boolean): PalBoneAnimation {
  const defaults = LEGACY_POSITION_DEFAULTS[name] ?? [0, 0, 0];
  const isBody = name === "body";
  const position: PalAxisChannels = [
    readLegacyKeyframes(reader, keyframeSize, easeBefore, { defaultValue: defaults[0], modelPixels: isBody, negate: isBody }),
    readLegacyKeyframes(reader, keyframeSize, easeBefore, { defaultValue: defaults[1], modelPixels: isBody, negate: !isBody }),
    readLegacyKeyframes(reader, keyframeSize, easeBefore, { defaultValue: defaults[2], modelPixels: isBody }),
  ];
  const rotation: PalAxisChannels = [
    readLegacyKeyframes(reader, keyframeSize, easeBefore, { negate: isBody }),
    readLegacyKeyframes(reader, keyframeSize, easeBefore, { negate: isBody }),
    readLegacyKeyframes(reader, keyframeSize, easeBefore),
  ];
  let bend: PalKeyframe[] = [];
  if (name !== "head") {
    readLegacyKeyframes(reader, keyframeSize, easeBefore); // Removed Y bend channel.
    bend = readLegacyKeyframes(reader, keyframeSize, easeBefore);
  }
  return { position, rotation, scale: [[], [], []], bend };
}

interface LegacyChannelOptions {
  defaultValue?: number;
  modelPixels?: boolean;
  negate?: boolean;
}

function readLegacyKeyframes(
  reader: BinaryReader,
  keyframeSize: number,
  easeBefore: boolean,
  options: LegacyChannelOptions = {},
): PalKeyframe[] {
  const count = reader.readInt32();
  if (count === -1) return [];
  if (count < -1 || count > MAX_COLLECTION_SIZE) throw new Error(`Invalid Emotecraft legacy keyframe count ${count}.`);
  const frames: PalKeyframe[] = [];
  let lastTick = 0;
  for (let index = 0; index < count; index++) {
    const tick = reader.readInt32();
    if (tick < lastTick) throw new Error("Emotecraft legacy keyframe ticks are not ordered.");
    const value = (reader.readFloat32() - (options.defaultValue ?? 0)) * (options.modelPixels ? 16 : 1) * (options.negate ? -1 : 1);
    if (!Number.isFinite(value)) throw new Error("Emotecraft legacy keyframe value is not finite.");
    const easing = EASINGS[reader.readUint8()] ?? "linear";
    reader.skip(keyframeSize - 9);
    frames.push({ startTick: lastTick, endTick: tick, start: frames.at(-1)?.end ?? (easeBefore ? value : 0), end: value, easing, easingArgs: [] });
    lastTick = tick;
  }
  if (!easeBefore && frames.length > 0) {
    let previousEasing = "easeinoutsine";
    for (const frame of frames) {
      const next = frame.easing;
      frame.easing = previousEasing;
      previousEasing = next;
    }
    const last = frames.at(-1)!;
    frames.push({ startTick: last.endTick, endTick: last.endTick + 0.001, start: last.end, end: last.end, easing: previousEasing, easingArgs: [] });
  }
  return frames;
}

function emptyBone(): PalBoneAnimation {
  return { rotation: [[], [], []], position: [[], [], []], scale: [[], [], []], bend: [] };
}

function boneHasFrames(bone: PalBoneAnimation | undefined): boolean {
  return Boolean(bone && [...bone.rotation, ...bone.position, ...bone.scale, bone.bend].some((channel) => channel.length > 0));
}

function readAnimationV6(reader: BinaryReader): PalAnimation {
  const flags = reader.readVarInt();
  const shouldLoop = (flags & 1) !== 0;
  const hold = (flags & 2) !== 0;
  const playerAnimator = (flags & 4) !== 0;
  const lengthTicks = reader.readFloat32();
  if (!Number.isFinite(lengthTicks) || lengthTicks < 0) throw new Error(`Invalid Emotecraft animation length ${lengthTicks}.`);
  const loopStartTick = shouldLoop && !hold ? reader.readFloat32() : 0;
  const beginTick = (flags & 32) !== 0 ? reader.readFloat32() : undefined;
  const endTick = (flags & 64) !== 0 ? reader.readFloat32() : undefined;
  const uuid = reader.readUuid();
  const bones = reader.readMap(() => reader.readProtocolString(), () => readBone(reader, playerAnimator));
  const effects = readEffects(reader);
  const pivots = reader.readMap(() => reader.readProtocolString(), () => [reader.readFloat32(), reader.readFloat32(), reader.readFloat32()] as const);
  const parents = reader.readMap(() => reader.readProtocolString(), () => reader.readProtocolString());
  return {
    uuid,
    lengthTicks,
    loop: !shouldLoop ? "once" : hold ? "hold" : "loop_from_tick",
    loopStartTick,
    format: playerAnimator ? "player_animator" : "geckolib",
    applyBendToOtherBones: (flags & 8) !== 0,
    easeBeforeKeyframe: (flags & 16) !== 0,
    ...(beginTick !== undefined ? { beginTick } : {}),
    ...(endTick !== undefined ? { endTick } : {}),
    bones,
    effects,
    pivots,
    parents,
  };
}

function readBone(reader: BinaryReader, startsFromDefault: boolean): PalBoneAnimation {
  const presence = reader.readVarInt();
  const channels: PalKeyframe[][] = [];
  for (let channel = 0; channel < 10; channel++) {
    const scale = channel >= 6 && channel <= 8;
    channels.push((presence & (1 << channel)) !== 0 ? readKeyframes(reader, startsFromDefault, scale) : []);
  }
  return {
    rotation: [channels[0], channels[1], channels[2]],
    position: [channels[3], channels[4], channels[5]],
    scale: [channels[6], channels[7], channels[8]],
    bend: channels[9],
  };
}

function readKeyframes(reader: BinaryReader, startsFromDefault: boolean, scale: boolean): PalKeyframe[] {
  const count = reader.readCount("keyframes");
  const result: PalKeyframe[] = [];
  let elapsed = 0;
  for (let index = 0; index < count; index++) {
    const combined = reader.readVarInt();
    const flags = combined & 0xf;
    const easing = EASINGS[combined >>> 4] ?? "linear";
    const end = (flags & 1) !== 0 ? reader.readFloat32() : readExpressionProgram(reader);
    const length = (flags & 4) !== 0 ? 0 : (flags & 8) !== 0 ? 1 : reader.readFloat32();
    if (!Number.isFinite(length) || length < 0) throw new Error(`Invalid Emotecraft keyframe length ${length}.`);
    const easingArgs = (flags & 2) !== 0 ? reader.readList(() => readExpressions(reader)) : [];
    const start = result.length > 0 ? result[result.length - 1].end : startsFromDefault ? (scale ? 1 : 0) : end;
    result.push({ startTick: elapsed, endTick: elapsed + length, start, end, easing, easingArgs });
    elapsed += length;
  }
  return result;
}

function readEffects(reader: BinaryReader): PalAnimation["effects"] {
  return {
    sounds: reader.readList(() => ({ tick: reader.readFloat32(), sound: reader.readProtocolString() })),
    particles: reader.readList(() => ({
      tick: reader.readFloat32(),
      effect: reader.readProtocolString(),
      locator: reader.readProtocolString(),
      script: reader.readProtocolString(),
    })),
    instructions: reader.readList(() => ({ tick: reader.readFloat32(), instruction: reader.readProtocolString() })),
  };
}

function readExpressionProgram(reader: BinaryReader): string {
  const expressions = readExpressions(reader);
  if (expressions.length === 0) return "0";
  return expressions.join(";");
}

function readExpressions(reader: BinaryReader): string[] {
  return reader.readList(() => readExpression(reader));
}

function readExpression(reader: BinaryReader): string {
  const type = reader.readUint8();
  switch (type) {
    case 0: {
      const operators = ["!", "-", "return "];
      const operator = operators[reader.readEnum(operators.length, "unary operator")];
      return `${operator}(${readExpression(reader)})`;
    }
    case 1: return `((${readExpression(reader)})?(${readExpression(reader)}):(${readExpression(reader)}))`;
    case 2: return `'${reader.readProtocolString().replaceAll("\\", "\\\\").replaceAll("'", "\\'")}'`;
    case 3: return ["break", "continue"][reader.readEnum(2, "statement operator")];
    case 4: return reader.readProtocolString();
    case 5: return String(reader.readFloat32());
    case 6: return `{${readExpressions(reader).join(";")}}`;
    case 7: return `${readExpression(reader)}(${readExpressions(reader).join(",")})`;
    case 8: {
      const operators = ["&&", "||", "<", "<=", ">", ">=", "+", "-", "*", "/", "->", "??", "=", "?", "==", "!="];
      const operator = operators[reader.readEnum(operators.length, "binary operator")];
      return `((${readExpression(reader)})${operator}(${readExpression(reader)}))`;
    }
    case 9: return `${readExpression(reader)}[${readExpression(reader)}]`;
    case 10: return `${readExpression(reader)}.${reader.readProtocolString()}`;
    default: throw new Error(`Unknown Emotecraft MoLang expression type ${type}.`);
  }
}

class BinaryReader {
  private readonly view: DataView;
  private offset = 0;

  constructor(private readonly bytes: Uint8Array) {
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }

  get remaining(): number {
    return this.bytes.length - this.offset;
  }

  readUint8(): number {
    this.ensure(1);
    return this.view.getUint8(this.offset++);
  }

  readInt32(): number {
    this.ensure(4);
    const value = this.view.getInt32(this.offset, false);
    this.offset += 4;
    return value;
  }

  readFloat32(): number {
    this.ensure(4);
    const value = this.view.getFloat32(this.offset, false);
    this.offset += 4;
    return value;
  }

  readVarInt(): number {
    let result = 0;
    for (let index = 0; index < 5; index++) {
      const byte = this.readUint8();
      result |= (byte & 0x7f) << (index * 7);
      if ((byte & 0x80) === 0) return result >>> 0;
    }
    throw new Error("Invalid Emotecraft VarInt.");
  }

  readCount(label: string): number {
    const count = this.readVarInt();
    if (count > MAX_COLLECTION_SIZE) throw new Error(`Emotecraft ${label} count ${count} exceeds the supported limit.`);
    return count;
  }

  readList<T>(read: () => T): T[] {
    return Array.from({ length: this.readCount("list") }, read);
  }

  readMap<T>(readKey: () => string, readValue: () => T): Record<string, T> {
    const result: Record<string, T> = {};
    const count = this.readCount("map");
    for (let index = 0; index < count; index++) result[readKey()] = readValue();
    return result;
  }

  readProtocolString(): string {
    const length = this.readVarInt();
    if (length === 0) return "";
    return this.decodeString(length);
  }

  readLegacyString(): string {
    const length = this.readInt32();
    if (length < 0) throw new Error(`Invalid Emotecraft string length ${length}.`);
    return this.decodeString(length);
  }

  readUuid(): string {
    const bytes = this.readBytes(16);
    const hex = [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  readBytes(length: number): Uint8Array {
    this.ensure(length);
    const result = this.bytes.slice(this.offset, this.offset + length);
    this.offset += length;
    return result;
  }

  readEnum(size: number, label: string): number {
    const ordinal = this.readUint8();
    if (ordinal >= size) throw new Error(`Unknown Emotecraft ${label} ${ordinal}.`);
    return ordinal;
  }

  subReader(length: number): BinaryReader {
    return new BinaryReader(this.readBytes(length));
  }

  skip(length: number): void {
    this.ensure(length);
    this.offset += length;
  }

  private decodeString(length: number): string {
    if (length > MAX_STRING_BYTES) throw new Error(`Emotecraft string length ${length} exceeds the supported limit.`);
    return new TextDecoder("utf-8", { fatal: true }).decode(this.readBytes(length));
  }

  private ensure(length: number): void {
    if (!Number.isSafeInteger(length) || length < 0 || length > this.remaining) throw new Error("Unexpected end of Emotecraft file.");
  }
}

function emptyToUndefined(value: string): string | undefined {
  return value.length > 0 ? value : undefined;
}
