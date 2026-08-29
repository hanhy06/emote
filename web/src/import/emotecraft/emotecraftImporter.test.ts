import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
import { validateEmoteAnimation } from "../../format/validator";
import type { EmotecraftFile, PalAxisChannels, PalBoneAnimation, PalKeyframe } from "./emotecraftBinary";
import { importEmotecraftFile } from "./emotecraftImporter";

const EMPTY_AXES: PalAxisChannels = [[], [], []];

describe("importEmotecraftFile", () => {
  it("flattens five bendable player parts into the sample's 19 independent skin slices", () => {
    const imported = importEmotecraftFile(file({
      torso: bone(bend(Math.PI / 4)),
      left_arm: bone(bend(Math.PI / 2)),
      right_arm: bone(bend(-Math.PI / 2)),
      left_leg: bone(bend(Math.PI / 3)),
      right_leg: bone(bend(-Math.PI / 3)),
    }), "dance.emotecraft");

    expect(Object.keys(imported.nodes)).toHaveLength(19);
    expect(imported.nodes.left_arm_0.defaultMatrix[3]).toBeCloseTo(-0.29296875);
    expect(imported.nodes.right_arm_0.defaultMatrix[3]).toBeCloseTo(0.29296875);
    expect(Object.values(imported.nodes).every((node) => node.space === "initiator")).toBe(true);
    expect(Object.values(imported.nodes).filter((node) => node.type === "anchor")).toEqual([]);
    expect(Object.values(imported.nodes).map((node) => node.type === "item_display" && node.suggestedSkin)).toEqual([
      { part: "head", order: 0 },
      { part: "body", order: 0 }, { part: "body", order: 1 },
      { part: "right_arm", order: 0 }, { part: "right_arm", order: 1 }, { part: "right_arm", order: 2 }, { part: "right_arm", order: 3 },
      { part: "left_arm", order: 0 }, { part: "left_arm", order: 1 }, { part: "left_arm", order: 2 }, { part: "left_arm", order: 3 },
      { part: "left_leg", order: 0 }, { part: "left_leg", order: 1 }, { part: "left_leg", order: 2 }, { part: "left_leg", order: 3 },
      { part: "right_leg", order: 0 }, { part: "right_leg", order: 1 }, { part: "right_leg", order: 2 }, { part: "right_leg", order: 3 },
    ]);
    expect(imported.animations[0].tracks.left_arm_0.transforms).toHaveLength(3);
    expect(imported.animations[0].tracks.left_arm_2.transforms[2].matrix).not.toEqual(imported.animations[0].tracks.left_arm_0.transforms[2].matrix);

    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "dance" });
    expect(Object.values(compiled.nodes).every((node) => node.parent === undefined)).toBe(true);
    expect(compiled.nodes.head.transform.position).toEqual([0, 1.875, 0]);
    expect(compiled.nodes.left_arm_0.transform.position).toEqual([-0.3515625, 1.40625, 0]);
    expect(compiled.nodes.right_arm_0.transform.position).toEqual([0.3515625, 1.40625, 0]);
    expect(compiled.nodes.left_arm_2.type === "item_display" && compiled.nodes.left_arm_2.skin).toEqual({ participant: "initiator", part: "left_arm", order: 2 });
    expect(() => validateEmoteAnimation(compiled)).not.toThrow();
  });

  it("keeps an unbent pose to six skin nodes and bakes custom pivot parents into world tracks", () => {
    const source = file({
      body: bone(undefined, axis(frame(0, 16))),
      hand_control: bone(undefined, axis(frame(0, 8))),
      head: bone(),
    });
    source.animation.pivots.hand_control = [0, 24, 0];
    source.animation.parents.head = "hand_control";
    const imported = importEmotecraftFile(source, "parent.emotecraft");

    expect(Object.keys(imported.nodes)).toEqual(["head", "body", "right_arm", "left_arm", "left_leg", "right_leg"]);
    expect(imported.animations[0].tracks.head.transforms[2].matrix[3]).not.toBeCloseTo(0);
    expect(imported.animations[0].tracks.head.transforms).toHaveLength(3);
  });

  it("treats numeric angular values as radians and expression angular values as degrees", () => {
    const numeric = importEmotecraftFile(file({ head: bone(undefined, undefined, axis(frame(0, Math.PI / 2))) }), "numeric.emotecraft");
    const expression = importEmotecraftFile(file({ head: bone(undefined, undefined, axis(frame(0, "90"))) }), "expression.emotecraft");
    expect(numeric.animations[0].tracks.head.transforms[2].matrix).toEqual(expression.animations[0].tracks.head.transforms[2].matrix);
    expect(numeric.animations[0].tracks.head.transforms[2].matrix[6]).toBeCloseTo(0.9375);
  });
});

function file(bones: Record<string, PalBoneAnimation>): EmotecraftFile {
  return {
    metadata: { name: "Dance", description: "Converted", author: "Tester", badges: [] },
    animation: {
      uuid: "00000000-0000-0000-0000-000000000000",
      lengthTicks: 2,
      loop: "once",
      loopStartTick: 0,
      format: "player_animator",
      applyBendToOtherBones: true,
      easeBeforeKeyframe: true,
      bones,
      effects: { sounds: [], particles: [], instructions: [] },
      pivots: {},
      parents: {},
    },
  };
}

function bone(bendFrames: PalKeyframe[] = [], position: PalAxisChannels = EMPTY_AXES, rotation: PalAxisChannels = EMPTY_AXES): PalBoneAnimation {
  return { position, rotation, scale: EMPTY_AXES, bend: bendFrames };
}

function bend(value: number): PalKeyframe[] {
  return [frame(0, value), frame(value, value)];
}

function axis(frameValue: PalKeyframe): PalAxisChannels {
  return [[frameValue], [], []];
}

function frame(start: number | string, end: number | string): PalKeyframe {
  return { startTick: 0, endTick: 2, start, end, easing: "linear", easingArgs: [] };
}
