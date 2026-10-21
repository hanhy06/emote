import { sourceSecondsTime } from "../../format/time";
import { fromArrayBuffer } from "@nbsjs/core";
import type { ConversionIssue } from "../../foundation/diagnostics";
import type { TimelineEventIR } from "../../domain/animationIR";
import type { EmotecraftFile } from "./emotecraftBinary";

const MINECRAFT_INSTRUMENTS = [
  "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute", "bell", "chime", "xylophone",
  "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling", "trumpet", "trumpet_exposed",
  "trumpet_weathered", "trumpet_oxidized",
] as const;

export interface EmotecraftSongConversion {
  events: TimelineEventIR[];
  diagnostics: ConversionIssue[];
}

export function convertEmotecraftSong(packet: NonNullable<EmotecraftFile["song"]>, durationTicks: number): EmotecraftSongConversion {
  if (packet.version !== 2) return {
    events: [],
    diagnostics: [{
      severity: "warning", code: "emotecraft_song_version_unsupported",
      message: `Embedded Emotecraft music version ${packet.version} is unsupported and was ignored; the animation was preserved.`,
    }],
  };
  const bytes = packet.bytes;
  let song;
  let vanillaInstrumentCount: number;
  try {
    const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    vanillaInstrumentCount = header.getUint16(0, true) === 0 ? header.getUint8(3) : 10;
    song = fromArrayBuffer(bytes.slice().buffer);
  } catch (error) {
    return {
      events: [],
      diagnostics: [{
        severity: "warning",
        code: "emotecraft_song_invalid",
        message: `The embedded Emotecraft NBS song could not be read and was ignored: ${error instanceof Error ? error.message : String(error)}`,
      }],
    };
  }

  const commandsByTime = new Map<number, string[]>();
  const solo = song.hasSolo();
  let ignoredCustomNotes = 0;
  let ignoredPitchNotes = 0;
  let truncatedNotes = 0;
  let ignoredPanning = false;
  for (const layer of song.layers) {
    if (solo && !layer.isSolo) continue;
    const layerVolume = layer.volume ?? 100;
    for (const [songTick, note] of layer.notes) {
      const instrument = note.instrument < vanillaInstrumentCount ? MINECRAFT_INSTRUMENTS[note.instrument] : undefined;
      if (!instrument) {
        ignoredCustomNotes++;
        continue;
      }
      const time = songTick / song.getTempo();
      if (time < 0 || time > durationTicks / 20) {
        truncatedNotes++;
        continue;
      }
      const volume = layerVolume * (note.velocity ?? 100) / 10_000;
      if (volume <= 0) continue;
      const pitch = 2 ** (((note.key ?? 45) - 45 + (note.pitch ?? 0) / 100) / 12);
      if (pitch > 2) {
        ignoredPitchNotes++;
        continue;
      }
      ignoredPanning ||= (layer.stereo ?? 0) !== 0 || (note.panning ?? 0) !== 0;
      const command = `playsound minecraft:block.note_block.${instrument} record @a ~ ~ ~ ${formatNumber(volume)} ${formatNumber(pitch)} 0`;
      const commands = commandsByTime.get(time) ?? [];
      commands.push(command);
      commandsByTime.set(time, commands);
    }
  }

  const diagnostics: ConversionIssue[] = [];
  if (ignoredCustomNotes > 0) diagnostics.push({
    severity: "warning",
    code: "emotecraft_song_custom_instruments_ignored",
    message: `${ignoredCustomNotes} embedded NBS note(s) use custom instruments whose audio is not stored in the Emotecraft file and were ignored.`,
  });
  if (ignoredPitchNotes > 0) diagnostics.push({
    severity: "warning",
    code: "emotecraft_song_pitch_ignored",
    message: `${ignoredPitchNotes} embedded NBS note(s) exceed the Minecraft playsound pitch limit of 2 and were ignored.`,
  });
  if (truncatedNotes > 0) diagnostics.push({
    severity: "warning",
    code: "emotecraft_song_notes_truncated",
    message: `${truncatedNotes} embedded NBS note(s) fall outside the animation duration and were ignored.`,
  });
  if (ignoredPanning) diagnostics.push({
    severity: "warning",
    code: "emotecraft_song_panning_ignored",
    message: "Embedded NBS stereo panning cannot be represented by Minecraft playsound events and was ignored.",
  });
  if (song.loop.enabled) diagnostics.push({
    severity: "warning",
    code: "emotecraft_song_loop_ignored",
    message: "The embedded NBS song loop is not independent from the emote timeline and was ignored.",
  });

  const events = [...commandsByTime.entries()]
    .sort(([first], [second]) => first - second)
    .map(([time, commands]) => ({ time: sourceSecondsTime(time), source: { type: "player" as const }, origin: { type: "root" as const }, action: { type: "commands" as const, commands } }));
  return { events, diagnostics };
}

function formatNumber(value: number): string {
  return Number(value.toPrecision(8)).toString();
}
