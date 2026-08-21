import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior } from "../format/emoteAnimation";
import { IDENTITY_MATRIX } from "../format/matrix";
import type { ImportedProject } from "./conversionSeed";
import {
  assignDocumentNodeSpace,
  assignDocumentSkinOrder,
  assignDocumentSkinPart,
  createConversionDocument,
  documentPartAssignments,
  documentPartOrders,
  documentSkinAssignments,
} from "./conversionDocument";

describe("ConversionDocument", () => {
  it("consumes import suggestions into editable node and skin state", () => {
    const document = createConversionDocument(project(), "Test adapter");

    expect(document.nodes.head.space).toBe("partner");
    expect(document.nodes.head_variant.space).toBe("initiator");
    expect(document.skinGroups.head).toEqual({
      nodeIds: ["head", "head_variant"],
      assignment: { part: "head", order: 2 },
    });
    expect(document.animations[0].output).toMatchObject({
      displayName: "Test",
      namespace: "test",
    });
    expect(document.nodes.head).not.toHaveProperty("suggestedSkin");
  });

  it("initializes metadata from each animation while keeping the suggested namespace", () => {
    const source = project();
    source.suggestedMetadata = { name: "Project name", description: "Project description" };
    source.animations.push({ ...source.animations[0], id: "second", name: "Second animation" });

    const document = createConversionDocument(source, "Test adapter");

    expect(document.animations.map(({ output }) => output)).toMatchObject([
      { namespace: "test", displayName: "Test", description: "Test emote." },
      { namespace: "test", displayName: "Second animation", description: "Second animation emote." },
    ]);
  });

  it("keeps grouped skin edits and node spaces in one document", () => {
    const initial = createConversionDocument(project(), "Test adapter");
    const selected = new Set(["head_variant"]);
    const assigned = assignDocumentSkinPart(initial, selected, "body");
    const ordered = assignDocumentSkinOrder(assigned, selected, 5);
    const moved = assignDocumentNodeSpace(ordered, selected, "scene");

    expect(documentPartAssignments(assigned)).toEqual({ head: "body", head_variant: "body" });
    expect(documentPartOrders(ordered)).toEqual({ head: 5, head_variant: 5 });
    expect(moved.nodes.head_variant.space).toBe("scene");
    expect(moved.skinGroups.head.assignment).toBeNull();
  });

  it("derives output skin ownership from each node space", () => {
    const document = createConversionDocument(project(), "Test adapter");

    expect(documentSkinAssignments(document)).toEqual({
      head: { participant: "partner", part: "head", order: 2 },
      head_variant: { participant: "initiator", part: "head", order: 2 },
    });
  });

  it("recognizes quoted and bare player-head item identifiers as skin candidates", () => {
    const source = project();
    source.nodes = {
      quoted: {
        id: "quoted", type: "item_display", itemStackSnbt: '{id:"minecraft:player_head",count:1}', itemDisplay: "none",
        defaultMatrix: IDENTITY_MATRIX, visible: true,
      },
      bare: {
        id: "bare", type: "item_display", itemStackSnbt: "{id:player_head,count:1}", itemDisplay: "none",
        defaultMatrix: IDENTITY_MATRIX, visible: true,
      },
      other: {
        id: "other", type: "item_display", itemStackSnbt: "{id:stone,count:1}", itemDisplay: "none",
        defaultMatrix: IDENTITY_MATRIX, visible: true,
      },
    };

    expect(Object.keys(createConversionDocument(source, "Test adapter").skinGroups)).toEqual(["quoted", "bare"]);
  });
});

function project(): ImportedProject {
  return {
    source: "emote_json",
    sourceName: "test.json",
    suggestedMetadata: { name: "Test", description: "Test emote" },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: "test",
    nodes: {
      head: {
        id: "head", type: "item_display", itemStackSnbt: "{id:player_head}", itemDisplay: "none",
        defaultMatrix: IDENTITY_MATRIX, visible: true, skinAssignmentGroup: "head",
        suggestedSkin: { participant: "partner", part: "head", order: 2 },
      },
      head_variant: {
        id: "head_variant", type: "item_display", itemStackSnbt: "{id:player_head}", itemDisplay: "none",
        defaultMatrix: IDENTITY_MATRIX, visible: true, skinAssignmentGroup: "head",
        suggestedSkin: { part: "head", order: 2 },
      },
    },
    animations: [{
      id: "test", name: "Test", durationTicks: 1, loop: "once", loopDelayTicks: 0,
      tracks: {}, events: { start: [], timeline: [], loop: [], stop: [] },
    }],
    diagnostics: [],
    resources: new Map(),
  };
}
