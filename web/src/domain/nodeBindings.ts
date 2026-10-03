export interface SourceNodeBinding {
  sourceNodeId: string;
  skinGroupId?: string;
}

export interface EditorNodeBinding extends SourceNodeBinding {
  editorNodeId: string;
}

export type RuntimeNodeBindings = Record<string, string>;

export interface EditorNodeBindingIdRemapper {
  editorNodeId(id: string): string;
  editorGroupId(id: string): string;
}

export interface NodeBindingIdRemapper {
  editorNodeId(id: string): string;
  runtimeNodeId(id: string): string;
}

export function remapEditorNodeBinding(binding: EditorNodeBinding, ids: EditorNodeBindingIdRemapper): EditorNodeBinding {
  return {
    ...binding,
    editorNodeId: ids.editorNodeId(binding.editorNodeId),
    ...(binding.skinGroupId ? { skinGroupId: ids.editorGroupId(binding.skinGroupId) } : {}),
  };
}

export function remapRuntimeNodeBindings(bindings: RuntimeNodeBindings, ids: NodeBindingIdRemapper): RuntimeNodeBindings {
  return Object.fromEntries(Object.entries(bindings)
    .map(([runtimeNodeId, editorNodeId]) => [ids.runtimeNodeId(runtimeNodeId), ids.editorNodeId(editorNodeId)]));
}
