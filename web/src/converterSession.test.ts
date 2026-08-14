import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior } from "./format/emoteAnimation";
import { IDENTITY_MATRIX } from "./format/matrix";
import type { ImportedProject } from "./import/types";
import {
  assignSessionOrder,
  assignSessionSkinPart,
  assignSessionSpace,
  createConverterSession,
  findSkinCandidates,
  selectSessionAnimation,
  updateSessionAnimationOptions,
} from "./converterSession";

describe("converter session skin assignment", () => {
  it("initializes suggested assignments, coordinate spaces, and metadata", () => {
    const session = createConverterSession(project(), "Test adapter");

    expect(session.assignments).toEqual({ head: "head", head_variant: "head" });
    expect(session.orders).toEqual({ head: 2, head_variant: 2 });
    expect(session.spaces).toEqual({ head: "partner", head_variant: "initiator" });
    expect(session.animationOptions[0]).toMatchObject({ minecraftVersion: "26.2", namespace: "test", name: "Test" });
  });

  it("caches metadata and settings for each selected animation", () => {
    const source = project();
    source.animations.push({ ...source.animations[0], id: "second", name: "Second", loopDelayTicks: 4 });
    const initial = createConverterSession(source, "Test adapter");
    const editedFirst = updateSessionAnimationOptions(initial, { ...initial.animationOptions[0], name: "First edited", cooldown: "2s" });
    const selectedSecond = selectSessionAnimation(editedFirst, 1);
    const editedSecond = updateSessionAnimationOptions(selectedSecond, { ...selectedSecond.animationOptions[1], name: "Second edited", cooldown: "3s" });
    const selectedFirst = selectSessionAnimation(editedSecond, 0);

    expect(selectedFirst.animationOptions[0]).toMatchObject({ name: "First edited", cooldown: "2s", loopDelay: "0t" });
    expect(selectedFirst.animationOptions[1]).toMatchObject({ name: "Second edited", cooldown: "3s", loopDelay: "4t" });
    expect(selectedFirst.previewFrameIndex).toBe(0);
  });

  it("moves assigned scene parts to initiator space", () => {
    const session = createConverterSession(project(), "Test adapter");
    session.selectedParts = new Set(["head_variant"]);
    session.spaces.head_variant = "scene";

    const result = assignSessionSkinPart(session, findSkinCandidates(session.project), "body");

    expect(result.assignments).toEqual({ head: "body", head_variant: "body" });
    expect(result.spaces.head_variant).toBe("initiator");
  });

  it("clears a logical skin group in scene space and updates its order together", () => {
    const session = createConverterSession(project(), "Test adapter");
    session.selectedParts = new Set(["head_variant"]);
    const candidates = findSkinCandidates(session.project);

    const ordered = assignSessionOrder(session, candidates, 5);
    const cleared = assignSessionSpace(ordered, candidates, "scene");

    expect(ordered.orders).toEqual({ head: 5, head_variant: 5 });
    expect(cleared.assignments).toEqual({ head: null, head_variant: null });
    expect(cleared.orders).toEqual({ head: null, head_variant: null });
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
