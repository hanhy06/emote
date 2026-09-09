import { readItemStack } from "../format/minecraftData";
import { describe, expect, it } from "vitest";
import { createConversionDocument } from "../domain/conversionDocument";
import type { ImportedProject } from "../domain/conversionSeed";
import { createDefaultPlayerBehavior } from "../format/emoteAnimation";
import { IDENTITY_MATRIX } from "../format/matrix";
import { createPreviewModel } from "./previewModel";

describe("createPreviewModel", () => {
  it("uses the node default before its first visibility keyframe", () => {
    expect(preview(0).parts.map((part) => part.nodeId)).toEqual(["head"]);
    expect(preview(3).parts.map((part) => part.nodeId)).toEqual(["head"]);
  });

  it("uses the latest visibility state at the preview tick", () => {
    expect(preview(5).parts).toEqual([]);
    expect(preview(9).parts.map((part) => part.nodeId)).toEqual(["head"]);
  });
});

function preview(previewFrameIndex: number) {
  const project: ImportedProject = {
    source: "emote_json",
    sourceName: "preview.json",
    suggestedMetadata: { name: "Preview", description: "Preview" },
    suggestedPlayer: createDefaultPlayerBehavior(),
    nodes: {
      head: {
        id: "head",
        type: "item_display",
        itemStack: readItemStack("{id:player_head}"),
        itemDisplay: "none",
        defaultMatrix: IDENTITY_MATRIX,
        visible: true,
        suggestedSkin: { part: "head", order: 0 },
      },
    },
    animations: [{
      id: "preview",
      name: "Preview",
      durationTicks: 10,
      playbackMode: "once",
      loopDelayTicks: 0,
      tracks: {
        head: {
          transforms: [],
          visibility: [
            { tick: 4, visible: false },
            { tick: 8, visible: true },
          ],
          nbt: [],
        },
      },
      events: { start: [], timeline: [], loop: [], stop: [] },
    }],
    diagnostics: [],
    resources: new Map(),
  };
  return createPreviewModel(createConversionDocument(project, "Test adapter"), 0, previewFrameIndex);
}
