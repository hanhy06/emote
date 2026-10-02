export interface SourceNodeBinding {
  sourceNodeId: string;
  skinGroupId?: string;
}

export interface EditorNodeBinding extends SourceNodeBinding {
  editorNodeId: string;
}

export interface NativeRuntimeBindings {
  editorNodeByRuntimeNode: Record<string, string>;
}

export interface EditorNodeBindingIdRemapper {
  editorNodeId(id: string): string;
  editorGroupId(id: string): string;
}

export interface NodeBindingIdRemapper extends EditorNodeBindingIdRemapper {
  runtimeNodeId(id: string): string;
}

export function remapEditorNodeBinding(binding: EditorNodeBinding, ids: EditorNodeBindingIdRemapper): EditorNodeBinding {
  return {
    ...binding,
    editorNodeId: ids.editorNodeId(binding.editorNodeId),
    ...(binding.skinGroupId ? { skinGroupId: ids.editorGroupId(binding.skinGroupId) } : {}),
  };
}

export function remapNativeRuntimeBindings(bindings: NativeRuntimeBindings, ids: NodeBindingIdRemapper): NativeRuntimeBindings {
  return {
    editorNodeByRuntimeNode: Object.fromEntries(Object.entries(bindings.editorNodeByRuntimeNode)
      .map(([runtimeNodeId, editorNodeId]) => [ids.runtimeNodeId(runtimeNodeId), ids.editorNodeId(editorNodeId)])),

  };
}
