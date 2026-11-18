import { expect, it } from "vitest";
import { createConversionDocument } from "../domain/conversionDocument";
import { exportAnimation } from "../export/projectExporter";
import { readInput } from "../import/formats";

const animationPacket = Uint8Array.from([0,0,0,8,16,1,153,6,0,0,0,47,0,0,0,0,4,65,160,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,4,98,111,100,121,1,1,1,63,201,15,219,65,160,0,0,0,0,0,0,0]);

async function exportSong(version: number, song: Uint8Array) {
  const bytes = new Uint8Array(animationPacket.length + 6 + song.length);
  bytes.set(animationPacket);
  bytes[5] = 2;
  bytes.set([3, version], animationPacket.length);
  new DataView(bytes.buffer).setInt32(animationPacket.length + 2, song.length);
  bytes.set(song, animationPacket.length + 6);
  const project = await readInput("emotecraft_binary", { name: "music.emotecraft", bytes });
  const [output] = await exportAnimation(createConversionDocument(project, "Emotecraft"), 0);
  return { project, animation: JSON.parse(await output.blob.text()) };
}

function nbs(notes: { instrument: number; key: number; pitch?: number }[], vanillaCount = 16, version = 5): Uint8Array {
  const bytes: number[] = [];
  const short = (value: number) => bytes.push(value & 255, (value >> 8) & 255);
  const int = (value: number) => { short(value); short(value >> 16); };
  const string = (value: string) => { const encoded = new TextEncoder().encode(value); int(encoded.length); bytes.push(...encoded); };
  short(version ? 0 : 1);
  if (version) { bytes.push(version, vanillaCount); if (version >= 3) short(1); }
  short(notes.length);
  for (let field = 0; field < 4; field++) string("");
  short(1000);
  bytes.push(0, 10, 4);
  for (let field = 0; field < 5; field++) int(0);
  string("");
  if (version >= 4) { bytes.push(0, 0); short(0); }
  short(1);
  for (const note of notes) {
    short(1);
    bytes.push(note.instrument, note.key);
    if (version >= 4) { bytes.push(100, 100); short(note.pitch ?? 0); }
  }
  short(0); short(0);
  for (const _note of notes) {
    string("");
    if (version >= 4) bytes.push(0);
    bytes.push(100);
    if (version >= 2) bytes.push(100);
  }
  const custom = notes.some((note) => note.instrument >= vanillaCount);
  bytes.push(custom ? 1 : 0);
  if (custom) { string("custom"); string("custom.ogg"); bytes.push(45, 0); }
  return Uint8Array.from(bytes);
}

it.each([
  [3, Uint8Array.of(0, 0, 0, 0), "emotecraft_song_version_unsupported"],
  [2, Uint8Array.of(0), "emotecraft_song_invalid"],
])("exports the complete animation when optional music version %i cannot be converted", async (version, song, warning) => {
  const baseline = await readInput("emotecraft_binary", { name: "music.emotecraft", bytes: animationPacket });
  const [baselineOutput] = await exportAnimation(createConversionDocument(baseline, "Emotecraft"), 0);
  const result = await exportSong(version, song);
  expect(result.animation).toEqual(JSON.parse(await baselineOutput.blob.text()));
  expect(result.project.diagnostics.map((issue) => issue.code)).toContain(warning);
});

it("exports supported NBS music as a timeline command", async () => {
  const result = await exportSong(2, nbs([{ instrument: 0, key: 45 }]));
  expect(result.animation.animation.duration).toBe("20t");
  expect(result.animation.animation.events.timeline).toEqual([{
    time: "0t", source: { type: "player" }, origin: { type: "root" },
    action: { type: "commands", commands: ["playsound minecraft:block.note_block.harp record @a ~ ~ ~ 1 1 0"] },
  }]);
  expect(result.project.diagnostics).toEqual([]);
});

it.each([[5, 16], [0, 10]])("uses the NBS version %i instrument boundary instead of the library's fixed boundary", async (version, vanillaCount) => {
  const result = await exportSong(2, nbs([{ instrument: 0, key: 45 }, { instrument: vanillaCount, key: 45 }], vanillaCount, version));
  expect(result.animation.animation.events.timeline[0].action.commands).toEqual(["playsound minecraft:block.note_block.harp record @a ~ ~ ~ 1 1 0"]);
  expect(result.project.diagnostics).toEqual([expect.objectContaining({ code: "emotecraft_song_custom_instruments_ignored", message: expect.stringContaining("1 embedded NBS note(s)") })]);
});

it("keeps instrument 16 when the NBS header declares twenty built-in instruments", async () => {
  const result = await exportSong(2, nbs([{ instrument: 16, key: 45 }], 20));
  expect(result.animation.animation.events.timeline[0].action.commands).toEqual(["playsound minecraft:block.note_block.trumpet record @a ~ ~ ~ 1 1 0"]);
  expect(result.project.diagnostics).toEqual([]);
});

it("omits unrepresentable pitches without clamping and keeps boundary pitches after detuning", async () => {
  const result = await exportSong(2, nbs([
    { instrument: 0, key: 33 }, { instrument: 0, key: 57 }, { instrument: 0, key: 69 },
    { instrument: 0, key: 57, pitch: 1 }, { instrument: 0, key: 58, pitch: -100 },
  ]));
  expect(result.animation.animation.events.timeline[0].action.commands).toEqual([
    "playsound minecraft:block.note_block.harp record @a ~ ~ ~ 1 0.5 0",
    "playsound minecraft:block.note_block.harp record @a ~ ~ ~ 1 2 0",
    "playsound minecraft:block.note_block.harp record @a ~ ~ ~ 1 2 0",
  ]);
  expect(result.project.diagnostics).toEqual([expect.objectContaining({ code: "emotecraft_song_pitch_ignored", message: expect.stringContaining("2 embedded NBS note(s)") })]);
});
