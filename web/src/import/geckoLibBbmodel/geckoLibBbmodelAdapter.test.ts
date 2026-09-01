import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
import { serializeEmoteAnimation } from "../../format/serializer";
import { geckoLibBbmodelAdapter } from "./geckoLibBbmodelAdapter";

const encoder = new TextEncoder();

describe("geckoLibBbmodelAdapter", () => {
  it("detects only GeckoLib Blockbench projects", () => {
    expect(geckoLibBbmodelAdapter.probe(input(project()))).toEqual({
      confidence: 100,
      reason: "matches a GeckoLib Blockbench project",
    });
    expect(geckoLibBbmodelAdapter.probe(input({ meta: { model_format: "java_block" } }))).toEqual({
      confidence: 0,
      reason: "not a GeckoLib Blockbench project",
    });
  });

  it("imports embedded geometry, nested bones, and baked transform tracks", async () => {
    const imported = await geckoLibBbmodelAdapter.import(input(project()));

    expect(imported.source).toBe("geckolib_bbmodel");
    expect(imported.suggestedNamespace).toBe("demo");
    expect(Object.keys(imported.nodes)).toEqual(["root", "child"]);
    expect(imported.nodes.root.type).toBe("item_display");
    expect(imported.nodes.child.type).toBe("anchor");
    expect(imported.nodes.root.defaultMatrix).toEqual([
      0.9375, 0, 0, 0,
      0, 0.9375, 0, 0,
      0, 0, 0.9375, 0,
      0, 0, 0, 1,
    ]);
    expect(imported.nodes.root.type === "item_display" && imported.nodes.root.playerHeadConversion?.matrix).toEqual([
      0.5, 0, 0, 0.125,
      0, 0.5, 0, 0.25,
      0, 0, 0.5, 0.125,
      0, 0, 0, 1,
    ]);
    expect(imported.nodes.root.type === "item_display" && imported.nodes.root.itemStackSnbt).toContain("minecraft:item_model");
    expect(imported.nodes.root.type === "item_display" && imported.nodes.root.suggestedSkin).toBeUndefined();
    expect([...imported.resources.keys()]).toEqual([
      "assets/demo/textures/item/test_model/texture.png",
      "assets/demo/models/item/test_model/root.json",
      "assets/demo/items/test_model/root.json",
    ]);

    const model = JSON.parse(new TextDecoder().decode(imported.resources.get("assets/demo/models/item/test_model/root.json"))) as {
      elements: { from: number[]; to: number[] }[];
    };
    expect(model.elements[0].from).toEqual([8, 8, 8]);
    expect(model.elements[0].to).toEqual([12, 12, 12]);

    const animation = imported.animations[0];
    expect(animation.durationTicks).toBe(2);
    expect(animation.loop).toBe("loop");
    expect(animation.loopDelayTicks).toBe(1);
    expect(animation.tracks.root.transforms.map((frame) => frame.tick)).toEqual([0, 1, 2]);
    expect(animation.tracks.root.transforms[2].matrix[3]).toBeCloseTo(0.9375);
    expect(animation.tracks.child.transforms[2].matrix[3]).toBeCloseTo(0.9375);
    expect(animation.tracks.child.transforms[2].matrix[7]).toBeCloseTo(0.9375);

    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2" });
    expect(() => serializeEmoteAnimation(compiled)).not.toThrow();
  });

  it("preserves GeckoLib bbmodel project axes at the import boundary", async () => {
    const value = project();
    value.groups[0].origin = [16, 0, 0];
    value.groups[0].rotation = [10, 20, 30];
    value.outliner[0].origin = [16, 0, 0];
    value.outliner[0].rotation = [10, 20, 30];

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    const expected = [
      0.7629353263, -0.4134090099, 0.3548646622, 0.9375,
      0.440480916, 0.8274038618, 0.0169015418, 0,
      -0.3206438844, 0.1529774167, 0.8675780422, 0,
      0, 0, 0, 1,
    ];
    imported.nodes.root.defaultMatrix.forEach((value, index) => expect(value).toBeCloseTo(expected[index]));
  });

  it("keeps positive GeckoLib arm X rotation pointing forward", async () => {
    const value = project();
    value.animations[0].animators.root.keyframes = [
      { channel: "rotation", time: 0, interpolation: "linear", data_points: [{ x: 90, y: 0, z: 0 }] },
    ];

    const imported = await geckoLibBbmodelAdapter.import(input(value));
    const pose = imported.animations[0].tracks.root.transforms[0].matrix;

    expect(pose[6]).toBeCloseTo(-0.9375);
    expect(pose[9]).toBeCloseTo(0.9375);
  });

  it("splits cubes in the same bone into independently assignable nodes", async () => {
    const value = project();
    value.elements.push({
      ...value.elements[0],
      uuid: "second_cube",
      name: "Second Cube",
      from: [4, 0, 0],
      to: [8, 4, 4],
    });
    value.outliner[0].children.splice(1, 0, "second_cube");

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(Object.keys(imported.nodes)).toEqual(["root", "root_second_cube", "child"]);
    expect(imported.nodes.root_second_cube.type).toBe("item_display");
    expect(imported.nodes.root.type === "item_display" && imported.nodes.root.suggestedSkin).toBeUndefined();
    expect(imported.nodes.root_second_cube.type === "item_display" && imported.nodes.root_second_cube.suggestedSkin).toBeUndefined();
    expect(imported.animations[0].tracks.root_second_cube.transforms).toEqual(imported.animations[0].tracks.root.transforms);
    expect([...imported.resources.keys()]).toContain("assets/demo/models/item/test_model/root_second_cube.json");
  });

  it("removes duplicate skin-layer cubes from the emote while preserving their resource models", async () => {
    const value = project();
    value.groups[0].name = "Right Arm";
    value.outliner[0].name = "Right Arm";
    value.elements.push(Object.assign({
      ...value.elements[0],
      uuid: "arm_layer",
      name: "Right Arm Layer",
    }, { inflate: 0.25 }));
    value.outliner[0].children.splice(1, 0, "arm_layer");

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(Object.keys(imported.nodes)).toEqual(["right_arm", "child"]);
    expect(imported.nodes.right_arm.type === "item_display" && imported.nodes.right_arm.suggestedSkin).toBeUndefined();
    expect([...imported.resources.keys()]).toContain("assets/demo/models/item/test_model/right_arm_right_arm_layer.json");
  });

  it("splits tall body-part cubes into ordered upper and lower player heads", async () => {
    const value = project();
    value.groups[0].name = "Right Arm";
    value.outliner[0].name = "Right Arm";
    value.elements[0].name = "Right Arm";
    value.elements[0].from = [4, 12, -2];
    value.elements[0].to = [8, 24, 2];
    value.elements[0].faces.north = { uv: [0, 0, 4, 12], texture: 0 };
    value.elements[0].faces.south = Object.assign({ uv: [0, 0, 4, 12], texture: 0 }, { rotation: 180 });
    Object.assign(value.elements[0].faces, {
      east: Object.assign({ uv: [0, 0, 12, 4], texture: 0 }, { rotation: 90 }),
      west: Object.assign({ uv: [0, 0, 12, 4], texture: 0 }, { rotation: 270 }),
    });

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(Object.keys(imported.nodes)).toEqual(["right_arm", "right_arm_right_arm_lower", "child"]);
    expect(imported.nodes.right_arm.type === "item_display" && imported.nodes.right_arm.suggestedSkin).toEqual({ part: "right_arm", order: 0 });
    expect(imported.nodes.right_arm_right_arm_lower.type === "item_display" && imported.nodes.right_arm_right_arm_lower.suggestedSkin)
      .toEqual({ part: "right_arm", order: 1 });
    expect(imported.nodes.right_arm.type === "item_display" && imported.nodes.right_arm.playerHeadConversion?.matrix[5]).toBeCloseTo(0.5);
    expect(imported.nodes.right_arm_right_arm_lower.type === "item_display"
      && imported.nodes.right_arm_right_arm_lower.playerHeadConversion?.matrix[5]).toBeCloseTo(1);
    expect(imported.nodes.right_arm.type === "item_display" && imported.nodes.right_arm.itemStackSnbt).toContain("minecraft:item_model");
    expect([...imported.resources.keys()].filter((path) => path.endsWith(".json"))).toHaveLength(4);

    const decodeModel = (path: string) => JSON.parse(new TextDecoder().decode(imported.resources.get(path))) as {
      elements: { faces: Record<string, { uv: number[] }> }[];
    };
    const upperFaces = decodeModel("assets/demo/models/item/test_model/right_arm.json").elements[0].faces;
    const lowerFaces = decodeModel("assets/demo/models/item/test_model/right_arm_right_arm_lower.json").elements[0].faces;
    expect(upperFaces.north.uv).toEqual([0, 0, 4, 4]);
    expect(lowerFaces.north.uv).toEqual([0, 4, 4, 12]);
    expect(upperFaces.south.uv).toEqual([0, 8, 4, 12]);
    expect(lowerFaces.south.uv).toEqual([0, 0, 4, 8]);
    expect(upperFaces.east.uv).toEqual([0, 0, 4, 4]);
    expect(lowerFaces.east.uv).toEqual([4, 0, 12, 4]);
    expect(upperFaces.west.uv).toEqual([8, 0, 12, 4]);
    expect(lowerFaces.west.uv).toEqual([0, 0, 8, 4]);
  });

  it("assigns lower limb skin slices to a matching descendant joint", async () => {
    const value = project();
    value.groups[0].name = "Right Arm";
    value.outliner[0].name = "Right Arm";
    value.elements[0].name = "Right Arm";
    value.elements[0].from = [4, 12, -2];
    value.elements[0].to = [8, 24, 2];
    value.elements[0].faces.north = { uv: [0, 0, 4, 12], texture: 0 };
    value.groups[1].name = "Right Forearm";
    value.groups[1].origin = [5, 18, 0];
    const child = value.outliner[0].children[1];
    if (typeof child === "string") throw new Error("Expected child group.");
    child.name = "Right Forearm";
    child.origin = [5, 18, 0];
    value.animations[0].animators = {
      ...value.animations[0].animators,
      child: {
        name: "Right Forearm",
        keyframes: [
          { channel: "rotation", time: 0, interpolation: "linear", data_points: [{ x: 0, y: 0, z: 0 }] },
          { channel: "rotation", time: 0.1, interpolation: "linear", data_points: [{ x: 45, y: 0, z: 0 }] },
        ],
      },
    } as unknown as typeof value.animations[0]["animators"];

    const imported = await geckoLibBbmodelAdapter.import(input(value));
    const skinNodes = Object.values(imported.nodes).filter((node) => node.type === "item_display" && node.suggestedSkin?.part === "right_arm");

    expect(skinNodes.map((node) => node.type === "item_display" && node.suggestedSkin?.order)).toEqual([0, 1, 1, 2, 2, 3]);
    expect(Object.keys(imported.nodes)).toEqual([
      "right_arm", "right_arm_right_arm_skin_1", "right_arm_right_arm_skin_joint_upper_1",
      "right_forearm", "right_forearm_right_arm_skin_2", "right_forearm_right_arm_skin_3",
    ]);
    expect(imported.nodes.right_arm_right_arm_skin_1.type === "item_display"
      && imported.nodes.right_arm_right_arm_skin_1.skinAssignmentGroup).toBe("right_arm_1");
    expect(imported.nodes.right_arm_right_arm_skin_joint_upper_1.type === "item_display"
      && imported.nodes.right_arm_right_arm_skin_joint_upper_1.skinAssignmentGroup).toBe("right_arm_1");
    expect(imported.nodes.right_arm_right_arm_skin_joint_upper_1.type === "item_display"
      && Math.abs(imported.nodes.right_arm_right_arm_skin_joint_upper_1.playerHeadConversion!.matrix[6])).toBeGreaterThan(0.1);
    expect(imported.animations[0].tracks.right_forearm.transforms[2].matrix)
      .not.toEqual(imported.animations[0].tracks.right_arm.transforms[2].matrix);

    expect(imported.animations[0].tracks.right_forearm_right_arm_skin_3.transforms[2].matrix)
      .not.toEqual(imported.animations[0].tracks.right_arm_right_arm_skin_1.transforms[2].matrix);
  });

  it("converts presegmented limb cubes and removes their skin-layer duplicates", async () => {
    const value = project();
    value.groups[0].name = "Right Arm";
    value.groups[0].origin = [6, 24, 0];
    value.outliner[0].name = "Right Arm";
    value.outliner[0].origin = [6, 24, 0];
    value.elements[0].name = "Right Arm";
    value.elements[0].from = [4, 18, -2];
    value.elements[0].to = [8, 24, 2];
    value.elements[0].faces.north = { uv: [0, 0, 4, 6], texture: 0 };
    value.groups[1].name = "Right Hand";
    value.groups[1].origin = [6, 18, 0];
    const child = value.outliner[0].children[1];
    if (typeof child === "string") throw new Error("Expected child group.");
    child.name = "Right Hand";
    child.origin = [6, 18, 0];
    child.children = ["lower", "lower_sleeve"] as typeof child.children;
    value.elements.push(
      { ...value.elements[0], uuid: "lower", name: "Right Arm", from: [4, 12, -2], to: [8, 18, 2], faces: { ...value.elements[0].faces, north: { uv: [0, 6, 4, 12], texture: 0 } } },
      { ...value.elements[0], uuid: "upper_sleeve", name: "Right Sleeve", from: [4, 18.25, -2], to: [8, 24, 2] },
      { ...value.elements[0], uuid: "lower_sleeve", name: "Right Sleeve", from: [4, 12, -2], to: [8, 17.75, 2] },
    );
    const rootChildren = value.outliner[0].children;
    rootChildren.splice(1, 0, "upper_sleeve");

    const imported = await geckoLibBbmodelAdapter.import(input(value));
    const skinNodes = Object.values(imported.nodes).filter((node) => node.type === "item_display" && node.suggestedSkin?.part === "right_arm");
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2" });
    const compiledItems = Object.values(compiled.nodes).filter((node) => node.type === "item_display");

    expect(skinNodes.map((node) => node.type === "item_display" && node.suggestedSkin?.order)).toEqual([0, 1, 1, 2, 2, 3]);
    expect(Object.keys(imported.nodes).some((id) => id.includes("sleeve"))).toBe(false);
    expect(compiledItems.every((node) => node.type === "item_display" && node.item_stack_snbt?.includes("minecraft:player_head") === true)).toBe(true);
  });

  it("reconnects stale animator UUIDs by a unique normalized bone name", async () => {
    const value = project();
    const rootAnimator = value.animations[0].animators.root;
    value.animations[0].animators = {
      stale_root: { ...rootAnimator, name: "r_o-o t" },
    } as unknown as typeof value.animations[0]["animators"];

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.animations[0].tracks.root.transforms[2].matrix[3]).toBeCloseTo(0.9375);
  });

  it("bakes Catmull-Rom interpolation", async () => {
    const value = project();
    value.animations[0].animators.root.keyframes[1].interpolation = "catmullrom";

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.animations[0].tracks.root.transforms.every((frame) => frame.matrix.every(Number.isFinite))).toBe(true);
  });

  it("bakes Bezier handles, pre/post values, easing arguments, and time-based Molang", async () => {
    const value = project();
    value.animations[0].length = 0.2;
    Object.assign(value.animations[0].animators.root, { keyframes: [
      {
        channel: "position",
        time: 0,
        interpolation: "bezier",
        bezier_right_time: [0.05, 0.05, 0.05],
        bezier_right_value: [4, 0, 0],
        data_points: [{ x: 0, y: 0, z: 0 }, { x: 4, y: 0, z: 0 }],
      },
      {
        channel: "position",
        time: 0.2,
        interpolation: "linear",
        easing: "step",
        easingArgs: [4],
        bezier_left_time: [-0.05, -0.05, -0.05],
        bezier_left_value: [-4, 0, 0],
        data_points: [{ x: "query.anim_time * 80", y: 0, z: 0 }, { x: 20, y: 0, z: 0 }],
      },
    ] });

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    const transforms = imported.animations[0].tracks.root.transforms;
    expect(transforms).toHaveLength(5);
    expect(transforms.every((frame) => frame.matrix.every(Number.isFinite))).toBe(true);
    expect(transforms[0].matrix[3]).toBeCloseTo(0.234375);
    expect(transforms[4].matrix[3]).toBeCloseTo(1.171875);
  });

  it("bakes GeckoLib easing into transform tracks", async () => {
    const value = project();
    Object.assign(value.animations[0].animators.root.keyframes[1], { easing: "easeOutCirc" });

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.animations[0].tracks.root.transforms[1].matrix[3]).toBeCloseTo(Math.sqrt(0.75) * 0.9375);
  });

  it("preserves an easing key pose that falls between Minecraft ticks", async () => {
    const value = project();
    value.animations[0].length = 0.2;
    value.animations[0].animators.root.keyframes = [
      { channel: "position", time: 0, interpolation: "linear", data_points: [{ x: 0, y: 0, z: 0 }] },
      { channel: "position", time: 0.125, interpolation: "linear", data_points: [{ x: 16, y: 0, z: 0 }] },
      { channel: "position", time: 0.2, interpolation: "linear", data_points: [{ x: 0, y: 0, z: 0 }] },
    ];
    Object.assign(value.animations[0].animators.root.keyframes[1], { easing: "easeInCubic" });
    Object.assign(value.animations[0].animators.root.keyframes[2], { easing: "easeOutQuart" });

    const imported = await geckoLibBbmodelAdapter.import(input(value));
    const translations = imported.animations[0].tracks.root.transforms.map((frame) => frame.matrix[3]);

    expect(translations).toContainEqual(expect.closeTo(0.9375));
  });

  it("keeps GeckoLib step transitions instantaneous after tick placement", async () => {
    const value = project();
    value.animations[0].length = 0.2;
    value.animations[0].animators.root.keyframes = [
      { channel: "position", time: 0, interpolation: "step", data_points: [{ x: 0, y: 0, z: 0 }] },
      { channel: "position", time: 0.125, interpolation: "linear", data_points: [{ x: 16, y: 0, z: 0 }] },
      { channel: "position", time: 0.2, interpolation: "linear", data_points: [{ x: 16, y: 0, z: 0 }] },
    ];

    const imported = await geckoLibBbmodelAdapter.import(input(value));
    const transition = imported.animations[0].tracks.root.transforms.find((frame) => Math.abs(frame.matrix[3] - 0.9375) < 1e-8);

    expect(transition?.interpolation).toEqual({ type: "step" });
  });

  it("rejects unknown GeckoLib easing names", async () => {
    const value = project();
    Object.assign(value.animations[0].animators.root.keyframes[1], { easing: "customEasing" });

    await expect(geckoLibBbmodelAdapter.import(input(value))).rejects.toThrow("unsupported easing customEasing");
  });

  it("keeps unknown GeckoLib Molang as a warned Create pose", async () => {
    const value = project();
    Object.assign(value.animations[0].animators.root.keyframes[1].data_points[0], { x: "v.runtime_speed * 16" });

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.animations[0]).toMatchObject({
      tracks: {},
      availability: { preview: "create_pose", exportable: true },
    });
    expect(imported.diagnostics).toContainEqual(expect.objectContaining({
      code: "geckolib_animation_molang_unavailable",
      sourcePath: "animations[0].animators.root",
    }));
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "runtime" });
    expect(compiled.timeline.tracks.root_z.position?.[1].value?.[0]).toBe("((v.runtime_speed * 16) * 0.0625)");
    expect(compiled.nodes.root.type).toBe("item_display");
    expect(() => serializeEmoteAnimation(compiled)).not.toThrow();
  });

  it("requires the texture to be embedded", async () => {
    const value = project();
    (value.textures[0] as { source?: string }).source = undefined;

    await expect(geckoLibBbmodelAdapter.import(input(value))).rejects.toThrow("must be embedded");
  });

  it("imports textureless animation-only bone projects", async () => {
    const value = project();
    value.elements = [];
    value.textures = [];
    value.outliner[0].children = [];

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.nodes.root.type).toBe("anchor");
    expect(imported.resources.size).toBe(0);
    expect(imported.resourceMinecraftVersion).toBeUndefined();
    expect(imported.animations[0].tracks.root.transforms).toHaveLength(3);
  });

  it("keeps hand item bone names as ordinary anchors", async () => {
    for (const name of ["RightHandItem", "leftItem"]) {
      const value = project();
      value.elements = [];
      value.textures = [];
      value.outliner[0].children = [];
      value.groups[0].name = name;
      value.outliner[0].name = name;

      const imported = await geckoLibBbmodelAdapter.import(input(value));
      const node = imported.nodes[name.toLowerCase()];

      expect(node).toMatchObject({ type: "anchor" });
      expect(node).not.toHaveProperty("suggestedHeldItemArm");
    }
  });

  it("hides item and cape bones without creating skin candidates", async () => {
    for (const accessoryName of ["left_item", "rightItem", "cape"]) {
      const value = project();
      value.groups[0].name = accessoryName;
      value.outliner[0].name = accessoryName;
      value.animations[0].animators.root.name = accessoryName;

      const imported = await geckoLibBbmodelAdapter.import(input(value));
      const node = Object.values(imported.nodes).find((candidate) => candidate.type === "item_display");

      expect(node?.type).toBe("item_display");
      expect(node?.type === "item_display" && node.visible).toBe(true);
      expect(node?.type === "item_display" && node.playerHeadConversion).toBeUndefined();
      expect(node?.type === "item_display" && node.suggestedSkin).toBeUndefined();
    }
  });

  it("ignores animation-only item and cape helper bones", async () => {
    const value = project();
    Object.assign(value.animations[0].animators, { cape_helper: {
      name: "cape",
      keyframes: [{ channel: "rotation", time: 0, interpolation: "linear", data_points: [{ x: 0, y: 0, z: 0 }] }],
    } });

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.animations[0].availability).toBeUndefined();
  });

  it("moves arbitrary multi-axis cube rotation into the display transform", async () => {
    const value = project();
    value.elements[0].origin = [2, 2, 2];
    value.elements[0].rotation = [17, 31, -13];

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.nodes.root.defaultMatrix.slice(0, 12).some((entry, index) => index % 5 !== 0 && Math.abs(entry) > 1e-5)).toBe(true);
    const model = JSON.parse(new TextDecoder().decode(imported.resources.get("assets/demo/models/item/test_model/root.json"))) as {
      elements: { rotation?: unknown }[];
    };
    expect(model.elements[0].rotation).toBeUndefined();
    expect(imported.animations[0].tracks.root.transforms.every((frame) => frame.matrix.every(Number.isFinite))).toBe(true);
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2" });
    expect(() => serializeEmoteAnimation(compiled)).not.toThrow();
  });

  it("imports locators as animated anchors", async () => {
    const value = project();
    value.elements.push({
      uuid: "hand_locator",
      name: "Hand Socket",
      type: "locator",
      position: [0, 16, 0],
      rotation: [10, 20, 30],
      ignore_inherited_scale: true,
    } as unknown as typeof value.elements[number]);
    value.outliner[0].children.push("hand_locator");

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.nodes.root_hand_socket.type).toBe("anchor");
    expect(imported.nodes.root_hand_socket.defaultMatrix[7]).toBeCloseTo(1);
    expect(imported.animations[0].tracks.root_hand_socket.transforms).toHaveLength(3);
    expect(imported.animations[0].tracks.root_hand_socket.transforms[2].matrix[3]).toBeCloseTo(0.9375);
  });

  it("converts GeckoLib sound, particle, and slash-command effect keyframes", async () => {
    const value = project();
    value.animations[0].length = 0.2;
    Object.assign(value.animations[0].animators, {
      effects: {
        type: "effect",
        keyframes: [
          { channel: "sound", time: 0.05, data_points: [{ effect: "minecraft:block.note_block.harp" }] },
          { channel: "particle", time: 0.1, data_points: [{ effect: "minecraft:happy_villager", script: "variable.size = 2" }] },
          { channel: "timeline", time: 0.2, data_points: [{ script: "/say hello\ncustom_instruction" }] },
        ],
      },
    });

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.animations[0].durationTicks).toBe(5);
    expect(imported.animations[0].events.timeline.map((event) => event.commands).flat()).toEqual([
      "playsound minecraft:block.note_block.harp master @s ~ ~ ~",
      "particle minecraft:happy_villager ~ ~ ~",
      "say hello",
    ]);
    expect(imported.diagnostics.map((diagnostic) => diagnostic.code)).toEqual([
      "geckolib_particle_script_ignored",
      "geckolib_custom_instruction_ignored",
    ]);
  });

  it("writes configured animated texture metadata into the resource pack", async () => {
    const value = project();
    Object.assign(value.textures[0], {
      frame_time: 3,
      frame_interpolate: true,
      frame_order_type: "custom",
      frame_order: "0 2 1",
    });

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    const metadata = JSON.parse(new TextDecoder().decode(imported.resources.get("assets/demo/textures/item/test_model/texture.png.mcmeta")));
    expect(metadata).toEqual({ animation: { frametime: 3, interpolate: true, frames: [0, 2, 1] } });
  });

  it("preserves GeckoLib hold-on-last-frame playback", async () => {
    const value = project();
    value.animations[0].loop = "hold_on_last_frame";

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.animations[0].loop).toBe("hold");
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2" });
    expect(compiled.settings.playback).toEqual({ mode: "hold", loop_delay: "0t" });
  });
});

function project() {
  return {
    meta: { format_version: "5.0", model_format: "geckolib_model" },
    name: "Test Model",
    geckolib_modid: "demo",
    resolution: { width: 16, height: 16 },
    elements: [{
      uuid: "cube",
      name: "Cube",
      type: "cube",
      from: [0, 0, 0],
      to: [4, 4, 4],
      origin: [0, 0, 0],
      rotation: [0, 0, 0],
      faces: {
        north: { uv: [0, 0, 4, 4], texture: 0 },
        south: { uv: [0, 0, 4, 4], texture: 0 },
      },
    }],
    groups: [
      { uuid: "root", name: "Root", origin: [0, 0, 0], rotation: [0, 0, 0] },
      { uuid: "child", name: "Child", origin: [0, 16, 0], rotation: [0, 0, 0] },
    ],
    outliner: [{
      uuid: "root",
      name: "Root",
      origin: [0, 0, 0],
      rotation: [0, 0, 0],
      children: ["cube", {
        uuid: "child",
        name: "Child",
        origin: [0, 16, 0],
        rotation: [0, 0, 0],
        children: [],
      }],
    }],
    textures: [{
      id: "0",
      name: "texture.png",
      source: "data:image/png;base64,iVBORw0KGgo=",
    }],
    animations: [{
      uuid: "animation",
      name: "animation.test.wave",
      length: 0.1,
      loop: "loop",
      loop_delay: 0.05,
      animators: {
        root: {
          name: "Root",
          keyframes: [
            { channel: "position", time: 0, interpolation: "linear", data_points: [{ x: 0, y: 0, z: 0 }] },
            { channel: "position", time: 0.1, interpolation: "linear", data_points: [{ x: 16, y: 0, z: 0 }] },
          ],
        },
      },
    }],
  };
}

function input(value: unknown) {
  return { name: "test.bbmodel", bytes: encoder.encode(JSON.stringify(value)) };
}
