import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { gzipSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../compiler/animationCompiler";
import { bdDatapackAdapter } from "../bdDatapack/bdDatapackAdapter";
import { bdProjectAdapter } from "./bdProjectAdapter";

describe("bdProjectAdapter", () => {
  it("reports the path of malformed scene input", async () => {
    const input = { name: "broken.bdengine", bytes: createProject({ isCollection: true, children: [{ isItemDisplay: "yes" }] }) };

    await expect(bdProjectAdapter.import(input)).rejects.toThrow("scene.children[0].isItemDisplay must be a boolean");
  });

  it.runIf(existsSync("C:/dev/minecraft/Project.bdengine") && existsSync("C:/dev/minecraft/emote/run/TEST/datapacks/emote.dance.zip"))(
    "reads the PRJ2 project and follows its exported dance matrices",
    async () => {
    const projectBytes = new Uint8Array(await readFile("C:/dev/minecraft/Project.bdengine"));
    const input = { name: "Project.bdengine", bytes: projectBytes };
    expect((await bdProjectAdapter.probe(input)).confidence).toBe(100);

    const project = await bdProjectAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "dance" });
    expect(Object.keys(animation.nodes)).toHaveLength(19);
    expect(animation.timeline.keyframes).toHaveLength(88);
    expect(animation.timeline.duration_ticks).toBe(176);

    const datapackBytes = new Uint8Array(await readFile("C:/dev/minecraft/emote/run/TEST/datapacks/emote.dance.zip"));
    const exported = await bdDatapackAdapter.import({ name: "emote.dance.zip", bytes: datapackBytes });
    const [expected] = compileImportedProject(exported, { minecraftVersion: "26.2", namespace: "dance" });
    let largestDifference = 0;
    let largestDifferenceAt = "";
    animation.timeline.keyframes.forEach((keyframe, frameIndex) => {
      for (let nodeIndex = 0; nodeIndex < 19; nodeIndex++) {
        const actualMatrix = keyframe.node_transforms?.[`display_${nodeIndex}`]?.matrix;
        const expectedMatrix = expected.timeline.keyframes[frameIndex].node_transforms?.[`dance_${nodeIndex}`]?.matrix;
        expect(actualMatrix).toBeDefined();
        expect(expectedMatrix).toBeDefined();
        const difference = maxDifference(actualMatrix!, expectedMatrix!);
        if (difference > largestDifference) {
          largestDifference = difference;
          largestDifferenceAt = `frame ${frameIndex}, node ${nodeIndex}`;
        }
      }
    });
    expect(largestDifference, largestDifferenceAt).toBeLessThan(0.001);
    },
  );
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

function maxDifference(first: readonly number[], second: readonly number[]): number {
  return Math.max(...first.map((value, index) => Math.abs(value - second[index])));
}
