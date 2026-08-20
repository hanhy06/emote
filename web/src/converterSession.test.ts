import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior } from "./format/emoteAnimation";
import { IDENTITY_MATRIX } from "./format/matrix";
import type { ImportedProject } from "./domain/conversionSeed";
import {
  DEFAULT_TARGET_MINECRAFT_VERSION,
  documentNodeSpaces,
  documentPartAssignments,
  documentPartOrders,
} from "./domain/conversionDocument";
import {
  assignSessionOrder,
  assignSessionHeldItemArm,
  assignSessionSkinPart,
  assignSessionSpace,
  createConverterSession,
  selectSessionAnimation,
  updateSessionAnimationOptions,
} from "./converterSession";

describe("converter session skin assignment", () => {
  it("initializes suggested assignments, coordinate spaces, and metadata", () => {
    const session = createConverterSession(project(), "Test adapter");

    expect(documentPartAssignments(session.document)).toEqual({ head: "head", head_variant: "head" });
    expect(documentPartOrders(session.document)).toEqual({ head: 2, head_variant: 2 });
    expect(documentNodeSpaces(session.document)).toEqual({ head: "partner", head_variant: "initiator" });
    expect(session.document.animations[0].output).toMatchObject({ namespace: "test", displayName: "Test" });
    expect(session.document.targetMinecraftVersion).toBe(DEFAULT_TARGET_MINECRAFT_VERSION);
  });

  it("caches metadata and settings for each selected animation", () => {
    const source = project();
    source.animations.push({ ...source.animations[0], id: "second", name: "Second", loopDelayTicks: 4 });
    const initial = createConverterSession(source, "Test adapter");
    const editedFirst = updateSessionAnimationOptions(initial, { ...initial.document.animations[0].output, displayName: "First edited", cooldown: "2s" });
    const selectedSecond = selectSessionAnimation(editedFirst, 1);
    const editedSecond = updateSessionAnimationOptions(selectedSecond, { ...selectedSecond.document.animations[1].output, displayName: "Second edited", cooldown: "3s" });
    const selectedFirst = selectSessionAnimation(editedSecond, 0);

    expect(selectedFirst.document.animations[0].output).toMatchObject({ displayName: "First edited", cooldown: "2s", loopDelay: "0t" });
    expect(selectedFirst.document.animations[1].output).toMatchObject({ displayName: "Second edited", cooldown: "3s", loopDelay: "4t" });
    expect(selectedFirst.previewFrameIndex).toBe(0);
  });

  it("moves assigned scene parts to initiator space", () => {
    const session = createConverterSession(project(), "Test adapter");
    session.selectedParts = new Set(["head_variant"]);
    session.document.nodes.head_variant.space = "scene";

    const result = assignSessionSkinPart(session, "body");

    expect(documentPartAssignments(result.document)).toEqual({ head: "body", head_variant: "body" });
    expect(result.document.nodes.head_variant.space).toBe("initiator");
  });

  it("clears a logical skin group in scene space and updates its order together", () => {
    const session = createConverterSession(project(), "Test adapter");
    session.selectedParts = new Set(["head_variant"]);
    const ordered = assignSessionOrder(session, 5);
    const cleared = assignSessionSpace(ordered, "scene");

    expect(documentPartOrders(ordered.document)).toEqual({ head: 5, head_variant: 5 });
    expect(documentPartAssignments(cleared.document)).toEqual({ head: null, head_variant: null });
    expect(documentPartOrders(cleared.document)).toEqual({ head: null, head_variant: null });
  });

  it("assigns a selected attachment point to a physical hand and participant space", () => {
    const source = project();
    source.nodes.hand = { id: "hand", type: "anchor", defaultMatrix: IDENTITY_MATRIX };
    const session = createConverterSession(source, "Test adapter");
    session.selectedParts = new Set(["hand"]);

    const assigned = assignSessionHeldItemArm(session, "right");

    expect(assigned.document.nodes.hand).toMatchObject({ type: "anchor", heldItemArm: "right", space: "initiator" });
    const cleared = assignSessionHeldItemArm(assigned, null);
    expect(cleared.document.nodes.hand).toMatchObject({ type: "anchor", heldItemArm: null });
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
        id: "head",
        type: "item_display",
        itemStackSnbt: "{id:player_head}",
        itemDisplay: "none",
        defaultMatrix: IDENTITY_MATRIX,
        visible: true,
        skinAssignmentGroup: "head",
        suggestedSkin: { participant: "partner", part: "head", order: 2 },
      },
      head_variant: {
        id: "head_variant",
        type: "item_display",
        itemStackSnbt: "{id:player_head}",
        itemDisplay: "none",
        defaultMatrix: IDENTITY_MATRIX,
        visible: true,
        skinAssignmentGroup: "head",
        suggestedSkin: { part: "head", order: 2 },
      },
    },
    animations: [{
      id: "test",
      name: "Test",
      durationTicks: 1,
      loop: "once",
      loopDelayTicks: 0,
      tracks: {},
      events: { start: [], timeline: [], loop: [], stop: [] },
    }],
    diagnostics: [],
    resources: new Map(),
  };
}
