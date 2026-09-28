import { describe, expect, it } from "vitest";
import { remapEditorNodeBinding, remapNativeRuntimeBindings } from "./nodeBindings";

describe("node binding remapping", () => {
  it("keeps the source identity while remapping editor identities", () => {
    expect(remapEditorNodeBinding(
      { sourceNodeId: "source/head", editorNodeId: "head", skinGroupId: "skin", spaceGroupId: "body" },
      { editorNodeId: (id) => `editor/${id}`, editorGroupId: (id) => `group/${id}` },
    )).toEqual({
      sourceNodeId: "source/head",
      editorNodeId: "editor/head",
      skinGroupId: "group/skin",
      spaceGroupId: "group/body",
    });
  });

  it("remaps runtime, editor, and group identities through their own paths", () => {
    expect(remapNativeRuntimeBindings(
      {
        editorNodeByRuntimeNode: { runtime_head: "editor_head" },
        editorSpaceGroupByRuntimeRoot: { runtime_root: "editor_group" },
      },
      {
        runtimeNodeId: (id) => `runtime/${id}`,
        editorNodeId: (id) => `editor/${id}`,
        editorGroupId: (id) => `group/${id}`,
      },
    )).toEqual({
      editorNodeByRuntimeNode: { "runtime/runtime_head": "editor/editor_head" },
      editorSpaceGroupByRuntimeRoot: { "runtime/runtime_root": "group/editor_group" },
    });
  });
});
