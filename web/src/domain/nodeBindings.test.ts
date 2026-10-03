import { describe, expect, it } from "vitest";
import { remapEditorNodeBinding, remapRuntimeNodeBindings } from "./nodeBindings";

describe("node binding remapping", () => {
  it("keeps the source identity while remapping editor identities", () => {
    expect(remapEditorNodeBinding(
      { sourceNodeId: "source/head", editorNodeId: "head", skinGroupId: "skin"},
      { editorNodeId: (id) => `editor/${id}`, editorGroupId: (id) => `group/${id}` },
    )).toEqual({
      sourceNodeId: "source/head",
      editorNodeId: "editor/head",
      skinGroupId: "group/skin",
    });
  });

  it("remaps runtime and editor identities through their own paths", () => {
    expect(remapRuntimeNodeBindings(
      { runtime_head: "editor_head" },
      {
        runtimeNodeId: (id) => `runtime/${id}`,
        editorNodeId: (id) => `editor/${id}`,
      },
    )).toEqual({ "runtime/runtime_head": "editor/editor_head" });
  });
});
