import { describe, expect, it } from "vitest";
import { compileConversionAnimation } from "../compiler/animationCompiler";
import { createDefaultPlayerBehavior } from "../format/emoteAnimation";
import { IDENTITY_MATRIX } from "../format/matrix";
import { createPreviewModel } from "../preview/previewModel";
import { combineConversionDocuments } from "./conversionBatch";
import { createConversionDocument } from "./conversionDocument";
import type { ImportedProject } from "./conversionSeed";

describe("combineConversionDocuments", () => {
  it("keeps each input's nodes scoped to its animations", () => {
    const combined = combineConversionDocuments([
      createConversionDocument(project("first.json", "same", "first_node"), "First format"),
      createConversionDocument(project("second.json", "same", "second_node"), "Second format"),
    ]);

    expect(combined.animations.map((animation) => animation.source.id)).toEqual(["same", "same_2"]);
    expect(Object.keys(compileConversionAnimation(combined, 0).nodes)).toEqual(["input_1__first_node"]);
    expect(Object.keys(compileConversionAnimation(combined, 1).nodes)).toEqual(["input_2__second_node"]);
    expect(createPreviewModel(combined, 0, 0).parts.map((part) => part.nodeId)).toEqual(["input_1__first_node"]);
  });

  it("uses emote as the default namespace", () => {
    const document = createConversionDocument(project("dance.json", "dance", "node"), "Test format");

    expect(document.animations[0].output.namespace).toBe("emote");
    expect(document.sequence.namespace).toBe("emote");
    expect(compileConversionAnimation(document, 0).id).toBe("emote:dance");
  });
});

function project(sourceName: string, animationId: string, nodeId: string): ImportedProject {
  return {
    source: "bedrock_animation_json",
    sourceName,
    suggestedMetadata: { name: animationId, description: `${animationId} animation` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: "source_namespace",
    nodes: {
      [nodeId]: {
        id: nodeId,
        type: "item_display",
        itemStack: { id: "minecraft:player_head" },
        itemDisplay: "none",
        defaultMatrix: IDENTITY_MATRIX,
        visible: true,
        suggestedSkin: { part: "head", order: 0 },
      },
    },
    animations: [{
      id: animationId,
      name: animationId,
      durationTicks: 1,
      playbackMode: "once",
      loopDelayTicks: 0,
      tracks: {},
      events: { start: [], timeline: [], loop: [], stop: [] },
    }],
    diagnostics: [],
    resources: new Map(),
  };
}
