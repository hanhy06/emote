import { gzipSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../compiler/animationCompiler";
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
    expect(animation.timeline.keyframes).toHaveLength(1);
    expect(animation.timeline.duration_ticks).toBe(2);
    expect(animation.timeline.keyframes[0].node_transforms?.display_0?.matrix).toEqual(IDENTITY_MATRIX);
    expect(() => serializeEmoteAnimation(animation)).not.toThrow();
  });
});

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
