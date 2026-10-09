import { readInput, detectInputFormat } from "../import/formats";
import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { assignDocumentSkinOrder, assignDocumentSkinPart, createConversionDocument, documentPartAssignments, updateDocumentAnimation } from "../domain/conversionDocument";
import { combineConversionDocuments } from "../domain/conversionBatch";
import { createPreviewModel } from "../preview/previewModel";
import { compileConversionAnimationArtifact } from "../compiler/animationCompiler";
import { createDocumentAnimationDownload, createDocumentAnimationBundleDownload } from "../export/projectExporter";
import { requireAnimation } from "../format/animation";
import { evaluatePoseIR } from "../domain/animationIRPose";
import { matrix4ToRowMajor } from "../format/matrix";
import { MolangBakeEvaluator } from "../preview/molangEvaluator";

const bytes = (path: string) => readFile(new URL(`../../../${path}`, import.meta.url));
const examplePath = "docs/design/animation-v5.example.json";

describe("schema 5 sample conversion", () => {
  it.each([
    ["docs/reference/aj/emote.ajblueprint", "animated_java_blueprint", "anvil"],
    ["docs/reference/bbmodel/emote.bbmodel", "geckolib_bbmodel", "cry"],
  ] as const)("keeps sample commands without a slash and warns without exposing their text in %s", async (path, format, name) => {
    const source = JSON.parse((await bytes(path)).toString());
    const animation = source.animations.find((animation: any) => animation.name === name);
    source.animations = [animation];
    const frames = Object.values(animation.animators).flatMap((animator: any) => animator.keyframes ?? [])
      .filter((frame: any) => ["function", "timeline"].includes(frame.channel)) as any[];
    const expected = frames.map((frame) => ({ time: frame.time, commands: frame.data_points.flatMap((point: any) => (point.function ?? point.script).split(/\r?\n/).map((line: string) => line.trim().replace(/^\//, "")).filter(Boolean)) }));
    animation.animators.effects = { type: "effect", keyframes: frames.map((frame, index) => ({ ...frame, channel: "timeline", data_points: [{ script: expected[index].commands.join("\n") }] })) };
    for (const [id, animator] of Object.entries(animation.animators) as [string, any][]) {
      if (id !== "effects" && animator.keyframes) animator.keyframes = animator.keyframes.filter((frame: any) => frame.channel !== "function");
    }
    const originalInput = JSON.stringify(source);
    const imported = await readInput(format, { name: path.split("/").at(-1)!, bytes: new TextEncoder().encode(originalInput) });
    const document = createConversionDocument(imported, "Sample");
    const output = compileConversionAnimationArtifact(document, 0).animation;
    expect(output.schema_version).toBe(5);
    expect(output.animation.events?.timeline?.map((event) => ({ time: event.time, commands: event.action.type === "commands" ? event.action.commands : [] })))
      .toEqual(expected.sort((first, second) => first.time - second.time));
    const warnings = imported.diagnostics.filter((issue) => issue.code.endsWith("instruction_approximated"));
    expect(warnings).toHaveLength(frames.length);
    for (const warning of warnings) {
      expect(warning.message).toContain(`${name} at `);
      for (const event of expected) for (const command of event.commands) expect(warning.message).not.toContain(command);
    }
    expect(JSON.stringify(source)).toBe(originalInput);
  });

  it("exports the existing AJ source IR without generating an intermediate runtime", async () => {
    const path = "docs/reference/aj/emote.ajblueprint";
    const imported = await readInput("animated_java_blueprint", { name: "emote.ajblueprint", bytes: await bytes(path) });
    imported.animations = [imported.animations.find((animation) => animation.name === "anvil")!];
    const source = imported.animations[0];
    expect(source.ir).toBeDefined();
    if (!source.ir) throw new Error("Missing Animated Java source IR.");
    const original = createConversionDocument(imported, "Sample");
    const document = assignDocumentSkinPart(original, new Set(Object.keys(original.skinCandidates)), null);
    const actual = compileConversionAnimationArtifact(document, 0).animation;
    expect(actual.schema_version).toBe(5);
    expect(actual.animation.events).toEqual(source.ir.animation.events);
    expect(actual.animation.tracks).toEqual(source.ir.animation.tracks);
    const expectedNodes = structuredClone(source.ir.nodes);
    for (const node of Object.values(expectedNodes)) if (node.source?.editor_node_id) {
      node.source.node_id ??= imported.nodeHints[String(node.source.editor_node_id)]?.sourceNodeId ?? node.source.editor_node_id;
      delete node.source.editor_node_id;
    }
    expect(actual.nodes).toEqual(expectedNodes);
    expect(Object.values(actual.nodes).every((node) => node.source?.runtime_aliases === undefined)).toBe(true);
    expect(Object.values(actual.nodes).every((node) => node.source?.editor_node_id === undefined)).toBe(true);
    expect(Object.keys(actual.nodes).some((id) => id.startsWith("group:"))).toBe(true);
    expect(Object.values(actual.nodes).some((node) => node.transform?.some((operation) => operation.op === "rotate_euler" && operation.order === "ZYX"))).toBe(true);
  });

  it("evaluates the existing AJ sample directly from the selected IR without changing export", async () => {
    const imported = await readInput("animated_java_blueprint", { name: "emote.ajblueprint", bytes: await bytes("docs/reference/aj/emote.ajblueprint") });
    imported.animations = [imported.animations.find((animation) => !animation.ir.animation.events?.timeline?.length)!];
    const original = createConversionDocument(imported, "Sample");
    const document = original;
    const actual = compileConversionAnimationArtifact(document, 0).animation;
    const before = JSON.stringify(actual);
    let checked = 0;
    const evaluator = new MolangBakeEvaluator({ error: { code: "sample", message: (value) => value } });
    for (const tick of [0, Math.floor(actual.animation.duration * 10), Math.floor(actual.animation.duration * 20)]) {
      const preview = createPreviewModel(document, document.animations[0], tick + 1);
      expect(preview.availability?.status).toBe("full");
      const pose = evaluatePoseIR(actual, tick / 20, (value, progress) => evaluator.evaluate(typeof value === "number" ? value : value.molang, { animationTime: tick / 20, keyframeLerpTime: progress }, "sample"));
      for (const part of preview.parts) {
        expect(part.conversionMatrix).toBeUndefined();
        matrix4ToRowMajor(pose[part.nodeId].matrix, part.nodeId).forEach((value, axis) => expect(value).toBeCloseTo(part.matrix[axis], 9));
        checked++;
      }
    }
    expect(checked).toBeGreaterThan(0);
    expect(JSON.stringify(compileConversionAnimationArtifact(document, 0).animation)).toBe(before);
  });

  it.each([
    ["docs/reference/aj/emote.ajblueprint", "animated_java_blueprint"],
    ["docs/reference/aj/sit.ajblueprint", "animated_java_blueprint"],
    ["docs/reference/bbmodel/emote.bbmodel", "geckolib_bbmodel"],
  ] as const)("preserves original transform key times in %s", async (path, format) => {
    const input = await bytes(path);
    const source = JSON.parse(input.toString());
    const imported = await readInput(format, { name: path.split("/").at(-1)!, bytes: input });
    const document = createConversionDocument(imported, "Sample");
    for (let index = 0; index < document.animations.length; index++) {
      const output = compileConversionAnimationArtifact(document, index).animation;
      expect(output.schema_version).toBe(5);
      const original = source.animations.find((a: any) => a.name === document.animations[index].sourceName);
      if (path === "docs/reference/aj/emote.ajblueprint" && original.name === "bat") {
        expect(output.nodes["group:42eeabad-b9fa-0dc5-bd3a-8d4a55bd769c"]).toBeUndefined();
        expect(output.nodes["group:3fb67721-9eef-8ce2-b455-f3b58a77403e"]).toBeDefined();
      }
      for (const [id, animator] of Object.entries(original.animators) as [string, any][]) {
        if (!output.nodes[`group:${id}`] && !output.nodes[id]) continue;
        for (const channel of ["position", "rotation", "scale"]) {
          const frames = (animator.keyframes ?? []).filter((f: any) => f.channel === channel);
          if (!frames.length) continue;
          const track = output.animation.tracks.find((t) => (t.target.node === `group:${id}` || t.target.node === id) && t.target.operation === channel);
          expect(track, `${original.name}/${id}/${channel}`).toBeDefined();
          if (!track) throw new Error("Missing source transform track.");
          expect(track.driver.type).toBe("curve");
          if (track.driver.type !== "curve") continue;
          expect(track.driver.keys.map((k) => k.time)).toEqual([...new Set(frames.map((f: any) => f.time))].sort((a: any, b: any) => a - b));
        }
      }
    }
  }, 60_000);

  it("uses collision-free filenames and preserves the existing v5 example in downloads", async () => {
    const input = await bytes(examplePath);
    const expected = requireAnimation(JSON.parse(input.toString()));
    const document = createConversionDocument(await readInput("emote_json", { name: "example.json", bytes: input }), "Sample");
    const original = document.animations[0];
    document.animations = ["music/song", "music.song", "music.song.1"].map((id) => ({ ...original, id: `emote:${id}` }));
    document.sequence = { ...document.sequence, namespace: "emote", idPath: "music/song" };
    const bundle = await createDocumentAnimationBundleDownload(document, true);
    expect(bundle.map((file) => file.fileName)).toEqual(["emote.music.song.json", "emote.music.song.2.json", "emote.music.song.1.json", "emote.music.song.1.1.json"]);
    const evaluator = new MolangBakeEvaluator({ error: { code: "sample", message: (expression) => expression } });
    for (let index = 0; index < document.animations.length; index++) {
      const [single] = await createDocumentAnimationDownload(document, index);
      expect(single.fileName).toBe(bundle[index].fileName);
      const actual = requireAnimation(JSON.parse(await single.blob.text()));
      expect(actual.id).toBe(document.animations[index].id);
      expect(actual.animation.tracks).toEqual(expected.animation.tracks);
      for (const time of [0, 0.01, 0.5, 1, 1.9, 2]) {
        const scalar = (value: number | { molang: string }, progress: number) => evaluator.evaluate(typeof value === "number" ? value : value.molang, { animationTime: time, keyframeLerpTime: progress }, "sample");
        const poses = evaluatePoseIR(actual, time, scalar);
        const reference = evaluatePoseIR(expected, time, scalar);
        for (const id of Object.keys(expected.nodes)) {
          expect(matrix4ToRowMajor(poses[id].matrix, id)).toEqual(matrix4ToRowMajor(reference[id].matrix, id));
          expect(poses[id].visible).toBe(reference[id].visible);
        }
      }
    }
    const ir = document.animations[0];
    const edited = updateDocumentAnimation(document, 0, { ...ir, id: "custom_namespace:music/song",
      metadata: { ...ir.metadata, name: "Edited example", description: "Updated metadata", custom: { value: 1 } },
      settings: { ...ir.settings, cooldown: 0.01, display_interpolation_ticks: 2, standalone: true },
      clip: { ...ir.clip, playback: { ...ir.clip.playback, mode: "loop", loop_start: 0.01, loop_delay: { molang: "v.delay" } } },
    });
    const editedOutput = compileConversionAnimationArtifact(edited, 0).animation;
    expect(editedOutput.id).toBe("custom_namespace:music/song");
    expect(editedOutput.metadata).toEqual(edited.animations[0].metadata);
    expect(editedOutput.settings).toEqual(edited.animations[0].settings);
    expect(editedOutput.animation.playback).toEqual(edited.animations[0].clip.playback);
    expect(compileConversionAnimationArtifact(edited, 0, false).animation.settings?.standalone).toBe(false);
    expect(edited.animations[0].settings?.standalone).toBe(true);
    expect(document.animations[0].metadata).toEqual(expected.metadata);
    const sequence = JSON.parse(await bundle[3].blob.text());
    expect(sequence.schema_version).toBe(5);
    expect(sequence.steps.map((step: { emote: string }) => step.emote)).toEqual(["emote:music/song", "emote:music.song", "emote:music.song.1"]);
    for (const file of bundle) expect(JSON.parse(await file.blob.text()).schema_version).toBe(5);
    const sequenceInput = { name: bundle[3].fileName, bytes: new TextEncoder().encode(await bundle[3].blob.text()) };
    expect(await detectInputFormat(sequenceInput)).toBe("emote_sequence");
    const importedSequence = await readInput("emote_sequence", sequenceInput);
    expect(importedSequence.id).toBe(sequence.id);
    expect(importedSequence.steps).toEqual(sequence.steps);
  });

  it("accepts the v5 Sequence sample and rejects old Animation and Sequence schemas", async () => {
    const old = { name: "emote.bat.json", bytes: await bytes("docs/sample/emote.bat.json") };
    await expect(detectInputFormat(old)).rejects.toMatchObject({ code: "unsupported_input" });
    await expect(readInput("emote_json", old)).rejects.toThrow("schema_version 5");
    const sequence = { name: "sequence.json", bytes: await bytes("docs/reference/sequence.json") };
    expect(await detectInputFormat(sequence)).toBe("emote_sequence");
    expect((await readInput("emote_sequence", sequence)).id).toBe(JSON.parse(new TextDecoder().decode(sequence.bytes)).id);
    const oldSequence = JSON.parse(new TextDecoder().decode(sequence.bytes));
    oldSequence.schema_version = 4;
    const oldSequenceInput = { name: "old-sequence.json", bytes: new TextEncoder().encode(JSON.stringify(oldSequence)) };
    await expect(detectInputFormat(oldSequenceInput)).rejects.toMatchObject({ code: "unsupported_input" });
    await expect(readInput("emote_sequence", oldSequenceInput)).rejects.toMatchObject({ code: "unsupported_sequence_schema", sourcePath: "schema_version" });
  });

  it("edits skin attachments and regions directly without accumulating transforms in the existing AJ sample", async () => {
    const imported = await readInput("animated_java_blueprint", { name: "emote.ajblueprint", bytes: await bytes("docs/reference/aj/emote.ajblueprint") });
    const original = createConversionDocument(imported, "Sample");
    const document = assignDocumentSkinPart(original, new Set(Object.keys(original.skinCandidates)), null);
    const groups = [...new Set(Object.values(document.skinCandidates).map((candidate) => candidate.groupId))].slice(0, 2);
    expect(groups).toHaveLength(2);
    const selected = new Set(Object.entries(document.skinCandidates).filter(([, candidate]) => groups.includes(candidate.groupId)).map(([id]) => id));
    let edited = assignDocumentSkinPart(document, selected, "head");
    const attachment = (id: string) => edited.nodes[id].attachments![edited.skinCandidates[id].attachmentId];
    const members = groups.map((group) => [...selected].filter((id) => document.skinCandidates[id].groupId === group));
    for (let index = 0; index < members.length; index++) for (const id of members[index]) {
      expect(attachment(id)).toMatchObject({ type: "player_skin", part: "head", region: { from: index / 2, to: (index + 1) / 2 } });
      const fit = edited.skinCandidates[id].fittingOperation!;
      expect(edited.nodes[id].transform!.filter((operation) => operation.id === fit.id)).toEqual([fit]);
    }
    expect(assignDocumentSkinPart(edited, selected, "head").nodes).toEqual(edited.nodes);
    edited = assignDocumentSkinOrder(edited, new Set(members[1]), 0);
    for (const id of members[1]) expect(attachment(id)).toMatchObject({ region: { from: 0, to: 0.5 } });
    for (const id of members[0]) expect(attachment(id)).toMatchObject({ region: { from: 0.5, to: 1 } });
    const snapshot = structuredClone(edited.nodes);
    for (let index = 0; index < edited.animations.length; index++) {
      const output = compileConversionAnimationArtifact(edited, index).animation;
      for (const id of selected) if (edited.animations[index].nodeIds.includes(id)) expect(output.nodes[id]).toEqual(edited.nodes[id]);
      createPreviewModel(edited, edited.animations[index], 2);
    }
    expect(edited.nodes).toEqual(snapshot);
    const id = members[0][0];
    edited = { ...edited, nodes: { ...edited.nodes, [id]: { ...edited.nodes[id], visible: false,
      transform: [...edited.nodes[id].transform!, { id: "user_offset", op: "translate", value: [1, 2, 3] }],
      attachments: { ...edited.nodes[id].attachments, user: { type: "external", key: "test:user", data: { keep: true } },
        [edited.skinCandidates[id].attachmentId]: { ...attachment(id), visible: false, entity_nbt: "{shadow_radius:0.25}" } },
    } } };
    expect(assignDocumentSkinPart(edited, selected, "head").nodes[id].transform).toEqual(edited.nodes[id].transform);
    expect(assignDocumentSkinOrder(edited, new Set(members[1]), 0).nodes[id].transform).toEqual(edited.nodes[id].transform);
    const cancelled = assignDocumentSkinPart(edited, selected, null);
    expect(cancelled.nodes[id].attachments![cancelled.skinCandidates[id].attachmentId]).toEqual({ ...document.skinCandidates[id].originalAttachment, visible: false, entity_nbt: "{shadow_radius:0.25}" });
    expect(cancelled.nodes[id].attachments!.user).toEqual(edited.nodes[id].attachments!.user);
    expect(cancelled.nodes[id].transform!.at(-1)).toEqual({ id: "user_offset", op: "translate", value: [1, 2, 3] });
    expect(cancelled.nodes[id].visible).toBe(false);
    expect(documentPartAssignments(cancelled)[id]).toBeNull();
    expect(compileConversionAnimationArtifact(cancelled, 0).generatedResourceReferences.size).toBeGreaterThan(0);
    expect(document.nodes[id]).not.toEqual(edited.nodes[id]);
    expect(document.nodes[id].attachments![document.skinCandidates[id].attachmentId]).toEqual(document.skinCandidates[id].originalAttachment);
  }, 60_000);

  it("shares source nodes once and keeps skin edits local to their input in the existing bbmodel sample", async () => {
    const imported = await readInput("geckolib_bbmodel", { name: "emote.bbmodel", bytes: await bytes("docs/reference/bbmodel/emote.bbmodel") });
    const source = createConversionDocument(imported, "Sample");
    expect(new Set(source.animations.flatMap((animation) => animation.nodeIds)).size).toBe(Object.keys(source.nodes).length);
    expect(source.animations.reduce((count, animation) => count + animation.nodeIds.length, 0)).toBeGreaterThan(Object.keys(source.nodes).length);
    const document = combineConversionDocuments([source, source]);
    const secondNodes = Object.fromEntries(Object.entries(document.nodes).filter(([id]) => id.startsWith("input_2__")));
    const id = Object.keys(document.skinCandidates).find((id) => id.startsWith("input_1__"))!;
    expect(id).toBeDefined();
    const edited = assignDocumentSkinPart(document, new Set([id]), "head");
    expect(Object.fromEntries(Object.entries(edited.nodes).filter(([id]) => id.startsWith("input_2__")))).toEqual(secondNodes);
    expect(new Set(edited.animations.map((animation) => animation.id)).size).toBe(edited.animations.length);
    for (let index = 0; index < edited.animations.length; index++) {
      const animation = edited.animations[index];
      const output = compileConversionAnimationArtifact(edited, index).animation;
      expect(Object.keys(output.nodes).sort()).toEqual([...animation.nodeIds].sort());
      for (const node of Object.values(output.nodes)) if (node.parent) expect(output.nodes[node.parent]).toBeDefined();
      for (const track of output.animation.tracks) expect(output.nodes[track.target.node]).toBeDefined();
    }
  }, 60_000);

  it("keeps different default node meanings separate and includes parent and event references from the existing v5 example", async () => {
    const imported = await readInput("emote_json", { name: "example.json", bytes: await bytes(examplePath) });
    const original = imported.animations[0];
    const alternate = structuredClone(original);
    alternate.id = "different_base";
    const changedNode = Object.keys(alternate.ir!.nodes).find((id) => alternate.ir!.nodes[id].transform?.length)!;
    alternate.ir!.nodes[changedNode].transform![0].value = alternate.ir!.nodes[changedNode].transform![0].value.map((value) => value + 1);
    imported.animations = [original, alternate];
    const document = createConversionDocument(imported, "Sample");
    const outputs = document.animations.map((_, index) => compileConversionAnimationArtifact(document, index).animation);
    const alternateId = document.animations[1].nodeIds.find((id) => !document.animations[0].nodeIds.includes(id))!;
    expect(alternateId).toBeDefined();
    expect(document.nodes[changedNode].transform).toEqual(original.ir!.nodes[changedNode].transform);
    expect(document.nodes[alternateId].transform).toEqual(alternate.ir!.nodes[changedNode].transform);
    expect(outputs[0].nodes[alternateId]).toBeUndefined();
    expect(outputs[1].nodes[changedNode]).toBeUndefined();
    for (const output of outputs) {
      for (const track of output.animation.tracks) expect(output.nodes[track.target.node]).toBeDefined();
      for (const node of Object.values(output.nodes)) if (node.parent) expect(output.nodes[node.parent]).toBeDefined();
    }
    const referenced = updateDocumentAnimation(document, 0, { ...document.animations[0], nodeIds: [] });
    const closure = compileConversionAnimationArtifact(referenced, 0).animation;
    expect(Object.keys(closure.nodes).length).toBeGreaterThan(0);
    for (const phase of Object.values(closure.animation.events ?? {})) for (const event of phase ?? []) {
      if (event.source.type === "node") expect(closure.nodes[event.source.node]).toBeDefined();
      if (event.origin.type === "node") expect(closure.nodes[event.origin.node]).toBeDefined();
    }
    for (const node of Object.values(closure.nodes)) if (node.parent) expect(closure.nodes[node.parent]).toBeDefined();
  });
});
