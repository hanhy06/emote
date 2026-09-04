import { readItemStack } from "./format/minecraftData";
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
  INITIAL_WORKSPACE,
  workspaceReducer,
  type WorkspaceState,
} from "./workspace";

describe("converter session skin assignment", () => {
  it("initializes suggested assignments, coordinate spaces, and metadata", () => {
    const session = opened(project()).session!;

    expect(documentPartAssignments(session.document)).toEqual({ head: "head", head_variant: "head" });
    expect(documentPartOrders(session.document)).toEqual({ head: 2, head_variant: 2 });
    expect(documentNodeSpaces(session.document)).toEqual({ head: "partner", head_variant: "initiator" });
    expect(session.document.animations[0].output).toMatchObject({ namespace: "test", displayName: "Test" });
    expect(session.document.targetMinecraftVersion).toBe(DEFAULT_TARGET_MINECRAFT_VERSION);
  });

  it("caches metadata and settings for each selected animation", () => {
    const source = project();
    source.animations.push({ ...source.animations[0], id: "second", name: "Second", loopDelayTicks: 4 });
    const initial = opened(source);
    const editedFirst = workspaceReducer(initial, {
      type: "animation_output_changed",
      output: { ...initial.session!.document.animations[0].output, displayName: "First edited", cooldown: "2s" },
    });
    const selectedSecond = workspaceReducer(editedFirst, { type: "animation_selected", index: 1 });
    const editedSecond = workspaceReducer(selectedSecond, {
      type: "animation_output_changed",
      output: { ...selectedSecond.session!.document.animations[1].output, displayName: "Second edited", cooldown: "3s" },
    });
    const selectedFirst = workspaceReducer(editedSecond, { type: "animation_selected", index: 0 }).session!;

    expect(selectedFirst.document.animations[0].output).toMatchObject({ displayName: "First edited", cooldown: "2s", loopDelay: "0t" });
    expect(selectedFirst.document.animations[1].output).toMatchObject({ displayName: "Second edited", cooldown: "3s", loopDelay: "4t" });
    expect(selectedFirst.previewFrameIndex).toBe(0);
  });

  it("moves assigned scene parts to initiator space", () => {
    const initial = opened(project());
    const selected = workspaceReducer(initial, { type: "node_selected", nodeId: "head_variant", additive: false });
    const moved = workspaceReducer(selected, { type: "node_space_assigned", space: "scene" });
    const result = workspaceReducer(moved, { type: "skin_part_assigned", part: "body" }).session!;

    expect(documentPartAssignments(result.document)).toEqual({ head: "body", head_variant: "body" });
    expect(result.document.nodes.head_variant.space).toBe("initiator");
  });

  it("clears a logical skin group in scene space and updates its order together", () => {
    const initial = opened(project());
    const selected = workspaceReducer(initial, { type: "node_selected", nodeId: "head_variant", additive: false });
    const ordered = workspaceReducer(selected, { type: "skin_order_assigned", order: 5 });
    const cleared = workspaceReducer(ordered, { type: "node_space_assigned", space: "scene" });

    expect(documentPartOrders(ordered.session!.document)).toEqual({ head: 5, head_variant: 5 });
    expect(documentPartAssignments(cleared.session!.document)).toEqual({ head: null, head_variant: null });
    expect(documentPartOrders(cleared.session!.document)).toEqual({ head: null, head_variant: null });
  });

});

function opened(source: ImportedProject): WorkspaceState {
  return workspaceReducer(INITIAL_WORKSPACE, { type: "open_succeeded", project: source, adapterLabel: "Test adapter" });
}

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
        itemStack: readItemStack("{id:player_head}"),
        itemDisplay: "none",
        defaultMatrix: IDENTITY_MATRIX,
        visible: true,
        skinAssignmentGroup: "head",
        suggestedSkin: { participant: "partner", part: "head", order: 2 },
      },
      head_variant: {
        id: "head_variant",
        type: "item_display",
        itemStack: readItemStack("{id:player_head}"),
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
      playbackMode: "once",
      loopDelayTicks: 0,
      tracks: {},
      events: { start: [], timeline: [], loop: [], stop: [] },
    }],
    diagnostics: [],
    resources: new Map(),
  };
}
