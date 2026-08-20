import { gzipSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
import { IDENTITY_MATRIX } from "../../format/matrix";
import { serializeEmoteAnimation } from "../../format/serializer";
import { bdProjectAdapter } from "./bdProjectAdapter";

describe("bdProjectAdapter", () => {
  it("reports the path of malformed scene input", async () => {
    const input = { name: "broken.bdengine", bytes: createProject({ isCollection: true, children: [{ isItemDisplay: "yes" }] }) };

    await expect(bdProjectAdapter.import(input)).rejects.toThrow("scene.children[0].isItemDisplay must be a boolean");
  });

  it("reads and compiles a self-contained PRJ2 project", async () => {
    const input = {
      name: "Project.bdengine",
      bytes: createProject({
        isCollection: true,
        listAnim: [{ name: "Default" }],
        children: [{ isItemDisplay: true, name: "minecraft:stone", transforms: IDENTITY_MATRIX }],
      }),
    };
    expect((await bdProjectAdapter.probe(input)).confidence).toBe(100);

    const project = await bdProjectAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "dance" });
    expect(Object.keys(animation.nodes)).toEqual(["display_0"]);
    expect(animation.timeline.tracks.display_0.position).toHaveLength(1);
    expect(animation.timeline.duration).toBe("2t");
    expect(animation.timeline.tracks.display_0.position?.[0].value).toEqual([0, 0, 0]);
    expect(() => serializeEmoteAnimation(animation)).not.toThrow();
  });

  it("removes shear introduced by non-uniform parent scale and child rotation", async () => {
    const angle = Math.PI / 4;
    const input = {
      name: "Sheared hierarchy.bdengine",
      bytes: createProject({
        isCollection: true,
        children: [{
          isCollection: true,
          transforms: [
            2, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1,
          ],
          children: [{
            isCollection: true,
            transforms: [
              Math.cos(angle), -Math.sin(angle), 0, 0,
              Math.sin(angle), Math.cos(angle), 0, 0,
              0, 0, 1, 0,
              0, 0, 0, 1,
            ],
            children: [{ isItemDisplay: true, name: "minecraft:stone", transforms: IDENTITY_MATRIX }],
          }],
        }],
      }),
    };

    const project = await bdProjectAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "stable" });
    const defaultTransform = animation.nodes.display_0.transform;
    const animatedRotation = animation.timeline.tracks.display_0.rotation?.[0].value;

    expect(animatedRotation).toBeDefined();
    expect([...defaultTransform.position, ...defaultTransform.rotation, ...defaultTransform.scale].every(Number.isFinite)).toBe(true);
    expect(animatedRotation?.every((value) => typeof value === "number" && Number.isFinite(value))).toBe(true);
  });

  it("accepts null curve metadata saved by BD Engine", async () => {
    const input = {
      name: "Null curves.bdengine",
      bytes: createProject({
        isCollection: true,
        children: [{
          isCollection: true,
          animation: [{ time: 0, ...transform(0), curve: null, curveFuncSave: null }],
          children: [{ isItemDisplay: true, name: "minecraft:stone", transforms: IDENTITY_MATRIX }],
        }],
      }),
    };

    await expect(bdProjectAdapter.import(input)).resolves.toMatchObject({ source: "bd_project" });
  });

  it("exports the stored timeline when the project lists multiple animations", async () => {
    const input = {
      name: "Multiple animations.bdengine",
      bytes: createProject({
        isCollection: true,
        listAnim: [
          { id: 1, name: "Default" },
          { id: 2, name: "Opening" },
          { id: 3, name: "Closing" },
        ],
        children: [{
          isCollection: true,
          animation: [{ time: 3, ...transform(4) }],
          children: [{ isItemDisplay: true, name: "minecraft:stone", transforms: IDENTITY_MATRIX }],
        }],
      }),
    };

    const project = await bdProjectAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "inventory" });

    expect(project.animations).toHaveLength(1);
    expect(project.animations[0].name).toBe("Multiple animations");
    expect(project.diagnostics).toEqual([{
      severity: "warning",
      code: "bd_project_multiple_animations",
      message: "BD Engine lists 3 animations, but the project file contains only one stored timeline. The stored timeline was imported.",
      sourcePath: "scene.listAnim",
    }]);
    expect(animation.metadata.name).toBe("Multiple animations");
    expect(animation.timeline.tracks.display_0.position?.map((keyframe) => keyframe.time)).toEqual(["0t", "2t", "4t", "6t"]);
    expect(animation.timeline.tracks.display_0.position?.at(-1)?.value?.[0]).toBeCloseTo(4);
    expect(() => serializeEmoteAnimation(animation)).not.toThrow();
  });

  it("converts the matching BD sound track into playsound timeline events", async () => {
    const input = {
      name: "Sound.bdengine",
      bytes: createProject({
        isCollection: true,
        listAnim: [{ id: 2, name: "Default" }],
        listSound: [{
          id: 2,
          name: "Default",
          tick: 3,
          tracks: [
            { id: "minecraft:block.note_block.harp", piano: [{ time: 0, pitch: 1.12345, volume: 0.876 }] },
            { id: "minecraft:entity.player.levelup", piano: [{ time: 2, pitch: 1, volume: 1 }] },
          ],
        }],
        children: [{ isItemDisplay: true, name: "minecraft:stone", transforms: IDENTITY_MATRIX }],
      }),
    };

    const project = await bdProjectAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "sound" });

    expect(animation.timeline.duration).toBe("9t");
    expect(animation.timeline.events?.timeline).toEqual([
      {
        time: "0t",
        source: { type: "server" },
        origin: { type: "root" },
        commands: ["playsound minecraft:block.note_block.harp block @a ~ ~ ~ 0.88 1.123"],
      },
      {
        time: "8t",
        source: { type: "server" },
        origin: { type: "root" },
        commands: ["playsound minecraft:entity.player.levelup block @a ~ ~ ~ 1 1"],
      },
    ]);
    expect(() => serializeEmoteAnimation(animation)).not.toThrow();
  });

  it("uses the animation at tick zero as the generated create pose", async () => {
    const input = {
      name: "Create pose.bdengine",
      bytes: createProject({
        isCollection: true,
        children: [{
          isCollection: true,
          name: "Animated parent",
          transforms: translationMatrix(1),
          defaultTransform: transform(2),
          animation: [{ time: 0, ...transform(3) }],
          children: [{ isItemDisplay: true, name: "minecraft:stone", transforms: IDENTITY_MATRIX }],
        }],
      }),
    };

    const project = await bdProjectAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "poses" });

    expect(animation.nodes.display_0.transform.position[0]).toBeCloseTo(3);
    expect(animation.timeline.tracks.display_0.position?.[0].value?.[0]).toBeCloseTo(3);
  });

  it("bakes sparse BD keyframes with their saved interpolation curve", async () => {
    const curveFuncSave = JSON.stringify([{ x: 0, y: 0 }, { x: 0.5, y: 0.25 }, { x: 1, y: 1 }]);
    const input = {
      name: "Sparse.bdengine",
      bytes: createProject({
        isCollection: true,
        listAnim: [{ name: "Default" }],
        children: [{
          isCollection: true,
          name: "Animated parent",
          defaultTransform: transform(0),
          animation: [{ time: 5, ...transform(3) }],
          children: [{
            isCollection: true,
            name: "Animated child",
            defaultTransform: transform(0),
            animation: [
              { time: 0, ...transform(0) },
              { time: 5, ...transform(10), curveFuncSave },
              { time: 10, ...transform(0), curveFuncSave },
            ],
            children: [{ isItemDisplay: true, name: "minecraft:stone", transforms: IDENTITY_MATRIX }],
          }],
        }],
      }),
    };

    const project = await bdProjectAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "sparse" });
    expect(animation.timeline.duration).toBe("22t");
    expect(animation.timeline.tracks.display_0.position?.map((keyframe) => keyframe.time)).toEqual(["0t", "2t", "4t", "6t", "8t", "10t", "12t", "14t", "16t", "18t", "20t"]);
    expect(animation.timeline.tracks.display_0.position?.slice(0, -1).every((keyframe) => keyframe.interpolation === "linear")).toBe(true);
    expect(animation.timeline.tracks.display_0.position?.[0].value?.[0]).toBeCloseTo(3);
    expect(animation.timeline.tracks.display_0.position?.[1].value?.[0]).toBeCloseTo(4);
    expect(animation.timeline.tracks.display_0.position?.[5].value?.[0]).toBeCloseTo(13);
    expect(() => serializeEmoteAnimation(animation)).not.toThrow();
  });

  it("rejects sparse timelines before allocating an excessive transform array", async () => {
    const input = {
      name: "Oversized.bdengine",
      bytes: createProject({
        isCollection: true,
        animation: [{ time: 200_000, ...transform(1) }],
        children: [{ isItemDisplay: true, name: "minecraft:stone", transforms: IDENTITY_MATRIX }],
      }),
    };

    await expect(bdProjectAdapter.import(input)).rejects
      .toThrow("more than 200000 transform samples");
  });
});

function transform(x: number) {
  return {
    position: { x, y: 0, z: 0 },
    rotation: { x: 0, y: 0, z: 0 },
    scale: { x: 1, y: 1, z: 1 },
  };
}

function translationMatrix(x: number): number[] {
  const matrix = [...IDENTITY_MATRIX];
  matrix[3] = x;
  return matrix;
}

function createProject(scene: unknown): Uint8Array {
  const encoder = new TextEncoder();
  const name = encoder.encode("scene.json");
  const content = encoder.encode(JSON.stringify(scene));
  const bytes = new Uint8Array(9 + 2 + name.length + 4 + content.length);
  const view = new DataView(bytes.buffer);
  bytes.set(encoder.encode("PRJ2"), 0);
  view.setUint8(4, 1);
  view.setUint32(5, 1, true);
  view.setUint16(9, name.length, true);
  bytes.set(name, 11);
  view.setUint32(11 + name.length, content.length, true);
  bytes.set(content, 15 + name.length);
  return new Uint8Array(gzipSync(bytes));
}
