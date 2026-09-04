import { describe, expect, it } from "vitest";
import { createConversionDocument, DEFAULT_TARGET_MINECRAFT_VERSION } from "../domain/conversionDocument";
import type { ImportedProject } from "../domain/conversionSeed";
import { createDefaultPlayerBehavior } from "../format/emoteAnimation";
import { emoteJsonAdapter } from "../import/emoteJson/emoteJsonAdapter";
import { convertSequenceInput } from "../import/emoteJson/sequenceJsonConverter";
import { exportDocumentAnimation, exportDocumentAnimationFiles } from "./projectExporter";

describe("reference target metadata", () => {
  it("records the selected target on single, batch and sequence output and preserves trusted commands", async () => {
    const document = createConversionDocument(project(), "test");
    document.targetMinecraftVersion = "26.3";
    const commands = ['/custom:command {Name:"unchanged"}', "", "  unknown command arguments  "];
    document.animations[0].source.events.start.push({ source: { type: "server" }, origin: { type: "root" }, commands });
    const single = JSON.parse(await exportDocumentAnimation(document, 0).blob.text());
    expect(single.target_minecraft_version).toBe("26.3");
    expect(single.timeline.events.start[0].commands).toEqual(commands);
    for (const file of exportDocumentAnimationFiles(document, true)) {
      expect(JSON.parse(await file.blob.text()).target_minecraft_version).toBe("26.3");
    }
    const imported = await emoteJsonAdapter.import({ name: "again.json", bytes: new TextEncoder().encode(JSON.stringify(single)) });
    expect(imported.animations[0].events.start[0].commands).toEqual(commands);
  });

  it.each([undefined, "26.1", "26.3", "future-snapshot", "toString"])("treats %s as a hint without blocking import", async (version) => {
    const sourceDocument = createConversionDocument(project(), "test");
    const source = JSON.parse(await exportDocumentAnimation(sourceDocument, 0).blob.text());
    source.target_minecraft_version = version;
    const imported = await emoteJsonAdapter.import({ name: "reference.json", bytes: new TextEncoder().encode(JSON.stringify(source)) });
    const document = createConversionDocument(imported, "test");
    expect(document.origin.minecraftVersion).toBe(version);
    expect(document.targetMinecraftVersion).toBe(version === "26.1" || version === "26.3" ? version : DEFAULT_TARGET_MINECRAFT_VERSION);
    document.targetMinecraftVersion = "26.2";
    const output = JSON.parse(await exportDocumentAnimation(document, 0).blob.text());
    expect(output.target_minecraft_version).toBe("26.2");
    expect(document.origin.minecraftVersion).toBe(version);
  });

  it("preserves sequence reference metadata without inventing a target on standalone conversion", async () => {
    const document = createConversionDocument(project(), "test");
    const sequenceFile = exportDocumentAnimationFiles(document, true).at(-1)!;
    const sequence = JSON.parse(await sequenceFile.blob.text());
    for (const version of [undefined, "26.3", "future-snapshot"]) {
      sequence.target_minecraft_version = version;
      const converted = convertSequenceInput({ name: "sequence.json", bytes: new TextEncoder().encode(JSON.stringify(sequence)) });
      expect(converted?.target_minecraft_version).toBe(version);
    }
  });
});

function project(): ImportedProject {
  return {
    source: "emote_json", sourceName: "test.json", suggestedMetadata: { name: "Test", description: "" },
    suggestedPlayer: createDefaultPlayerBehavior(), resources: new Map(), diagnostics: [],
    nodes: { root: { type: "anchor", id: "root", defaultMatrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1] } },
    animations: [{ id: "test", name: "Test", durationTicks: 1, playbackMode: "once", loopDelayTicks: 0, tracks: {}, events: { start: [], timeline: [], loop: [], stop: [] } }],
  };
}
