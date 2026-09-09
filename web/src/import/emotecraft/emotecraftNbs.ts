import { fromArrayBuffer } from "@nbsjs/core";
import type { ImportedTimelineEvent, ImportDiagnostic } from "../../domain/conversionSeed";

const MINECRAFT_INSTRUMENTS = [
  "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute", "bell", "chime", "xylophone",
  "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling", "trumpet", "trumpet_exposed",
  "trumpet_weathered", "trumpet_oxidized",
] as const;

export interface EmotecraftSongConversion {
  events: ImportedTimelineEvent[];
  diagnostics: ImportDiagnostic[];
}

export function convertEmotecraftSong(bytes: Uint8Array, durationTicks: number): EmotecraftSongConversion {
  let song;
  try {
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

  const commandsByTick = new Map<number, string[]>();
  const solo = song.hasSolo();
  let ignoredCustomNotes = 0;
  let truncatedNotes = 0;
  let ignoredPanning = false;
  for (const layer of song.layers) {
    if (solo && !layer.isSolo) continue;
    const layerVolume = layer.volume ?? 100;
    for (const [songTick, note] of layer.notes) {
      const instrument = MINECRAFT_INSTRUMENTS[note.instrument];
      if (!instrument) {
        ignoredCustomNotes++;
        continue;
      }
      const tick = Math.round(songTick * 20 / song.getTempo());
      if (tick < 0 || tick > durationTicks) {
        truncatedNotes++;
        continue;
      }
      const volume = layerVolume * (note.velocity ?? 100) / 10_000;
      if (volume <= 0) continue;
      ignoredPanning ||= (layer.stereo ?? 0) !== 0 || (note.panning ?? 0) !== 0;
      const pitch = 2 ** (((note.key ?? 45) - 45 + (note.pitch ?? 0) / 100) / 12);
      const command = `playsound minecraft:block.note_block.${instrument} record @a ~ ~ ~ ${formatNumber(volume)} ${formatNumber(pitch)} 0`;
      const commands = commandsByTick.get(tick) ?? [];
      commands.push(command);
      commandsByTick.set(tick, commands);
    }
  }

  const diagnostics: ImportDiagnostic[] = [];
  if (ignoredCustomNotes > 0) diagnostics.push({
    severity: "warning",
    code: "emotecraft_song_custom_instruments_ignored",
    message: `${ignoredCustomNotes} embedded NBS note(s) use custom instruments whose audio is not stored in the Emotecraft file and were ignored.`,
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

  const events = [...commandsByTick.entries()]
    .sort(([first], [second]) => first - second)
    .map(([tick, commands]) => ({ tick, source: { type: "player" as const }, origin: { type: "root" as const }, commands }));
  return { events, diagnostics };
}

function formatNumber(value: number): string {
  return Number(value.toPrecision(8)).toString();
}
