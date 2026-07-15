import type { MouseEvent } from "react";
import type { PlayerHeadPart } from "../preview/playerHeadPart";
import {
  SKIN_PARTS,
  type PartAssignments,
  type PartOrders,
  type SkinPartId,
} from "../preview/skinMapping";

interface AssignmentPanelProps {
  parts: PlayerHeadPart[];
  assignments: PartAssignments;
  orders: PartOrders;
  selectedParts: ReadonlySet<number>;
  hasSelectedAssignment: boolean;
  onAssignPart: (skinPart: SkinPartId | null) => void;
  onAssignOrder: (order: number) => void;
  onSelectPart: (partIndex: number, additive: boolean) => void;
}

export function AssignmentPanel({
  parts,
  assignments,
  orders,
  selectedParts,
  hasSelectedAssignment,
  onAssignPart,
  onAssignOrder,
  onSelectPart,
}: AssignmentPanelProps) {
  const hasSelection = selectedParts.size > 0;

  function handlePartClick(event: MouseEvent<HTMLButtonElement>, partIndex: number) {
    onSelectPart(partIndex, event.ctrlKey || event.metaKey || event.shiftKey);
  }

  return (
    <aside>
      <p><strong>Left/right reference:</strong> Directions are shown from behind the character. They appear reversed from the front.</p>
      <p>Click a box to select all pieces at the same position.</p>
      <div className="assignment-buttons">
        {SKIN_PARTS.map((part) => (
          <button type="button" key={part.id} disabled={!hasSelection} onClick={() => onAssignPart(part.id)}>
            <i style={{ backgroundColor: part.color }} />{part.label}
          </button>
        ))}
        <button type="button" disabled={!hasSelection} onClick={() => onAssignPart(null)}>Unassigned</button>
      </div>
      <p><strong>Skin order:</strong> Use 0 for the first piece and 1 for the second piece of the same body part.</p>
      <div className="assignment-buttons">
        <button type="button" disabled={!hasSelectedAssignment} onClick={() => onAssignOrder(0)}>Order 0</button>
        <button type="button" disabled={!hasSelectedAssignment} onClick={() => onAssignOrder(1)}>Order 1</button>
      </div>
      <ul className="part-list">
        {parts.map((part) => (
          <li key={part.partIndex}>
            <button
              type="button"
              className={selectedParts.has(part.partIndex) ? "selected" : ""}
              onClick={(event) => handlePartClick(event, part.partIndex)}
            >
              <span>#{part.partIndex}</span>
              <span>{assignmentLabel(assignments[part.partIndex], orders[part.partIndex])}</span>
            </button>
          </li>
        ))}
      </ul>
    </aside>
  );
}

function assignmentLabel(assignment: SkinPartId | null | undefined, order: number | null | undefined): string {
  const label = SKIN_PARTS.find((part) => part.id === assignment)?.label ?? "Unassigned";
  return assignment && order != null ? `${label} · ${order}` : label;
}
