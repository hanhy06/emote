import { Instrument, Note, Song, toArrayBuffer } from "@nbsjs/core";
import { describe, expect, it } from "vitest";
import { convertEmotecraftSong } from "./emotecraftNbs";

describe("convertEmotecraftSong", () => {
  it("converts built-in NBS instruments, tempo, volume, and pitch into grouped timeline events", () => {
    const song = new Song();
    song.setTempo(10);
    const layer = song.layers.create();
    layer.volume = 50;
    layer.notes.add(2, new Note(0, { key: 57, velocity: 80 }));
    layer.notes.add(4, new Note(15, { key: 45 }));

    const converted = convertEmotecraftSong(new Uint8Array(toArrayBuffer(song)), 20);

    expect(converted.diagnostics).toEqual([]);
    expect(converted.events).toEqual([
      {
        tick: 4,
        source: { type: "player" },
        origin: { type: "root" },
        commands: ["playsound minecraft:block.note_block.harp record @a ~ ~ ~ 0.4 2 0"],
      },
      {
        tick: 8,
        source: { type: "player" },
        origin: { type: "root" },
        commands: ["playsound minecraft:block.note_block.pling record @a ~ ~ ~ 0.5 1 0"],
      },
    ]);
  });

  it("warns when a song uses audio that is not embedded or notes outside the emote", () => {
    const song = new Song();
    const custom = song.instruments.add(new Instrument({ name: "Custom", soundFile: "custom.ogg" }));
    const customId = Object.entries(song.instruments.all).find(([, instrument]) => instrument === custom)![0];
    const layer = song.layers.create();
    layer.notes.add(0, new Note(Number(customId)));
    layer.notes.add(100, new Note(0));

    const converted = convertEmotecraftSong(new Uint8Array(toArrayBuffer(song)), 20);

    expect(converted.events).toEqual([]);
    expect(converted.diagnostics.map((diagnostic) => diagnostic.code)).toEqual([
      "emotecraft_song_custom_instruments_ignored",
      "emotecraft_song_notes_truncated",
    ]);
  });

  it("keeps animation conversion available when the optional song is malformed", () => {
    const converted = convertEmotecraftSong(new Uint8Array([1, 2, 3]), 20);
    expect(converted.events).toEqual([]);
    expect(converted.diagnostics[0].code).toBe("emotecraft_song_invalid");
  });
});
