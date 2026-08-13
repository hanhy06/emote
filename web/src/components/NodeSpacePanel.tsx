import type { TargetedEvent } from "preact";
import type { NodeSpace } from "../format/emoteAnimation";
import type { ImportedNode } from "../import/types";

interface NodeSpacePanelProps {
  nodes: Readonly<Record<string, ImportedNode>>;
  spaces: Readonly<Record<string, NodeSpace>>;
  onChange: (nodeId: string, space: NodeSpace) => void;
}

export function NodeSpacePanel({ nodes, spaces, onChange }: NodeSpacePanelProps) {
  function handleChange(event: TargetedEvent<HTMLSelectElement>, nodeId: string) {
    onChange(nodeId, event.currentTarget.value as NodeSpace);
  }

  return (
    <details className="node-space-panel">
      <summary>All node coordinate spaces</summary>
      <p>Scene nodes use the shared origin. Initiator and partner nodes use the participant roots from the sequence.</p>
      <div className="node-space-list">
        {Object.entries(nodes).map(([nodeId, node]) => (
          <label key={nodeId}>
            <span><strong>{nodeId}</strong><small>{node.type}</small></span>
            <select value={spaces[nodeId] ?? "scene"} onChange={(event) => handleChange(event, nodeId)}>
              <option value="scene">Scene</option>
              <option value="initiator">Initiator</option>
              <option value="partner">Partner</option>
            </select>
          </label>
        ))}
      </div>
    </details>
  );
}
