import type { MouseEvent } from "react";
import type { PlayerHeadPart } from "../converter/partParser";
import {
  isLimbPart,
  SKIN_PARTS,
  type PartAssignments,
  type PartOrders,
  type SkinPartId,
} from "../converter/skinMapping";

interface AssignmentPanelProps {
  parts: PlayerHeadPart[];
  assignments: PartAssignments;
  orders: PartOrders;
  selectedParts: ReadonlySet<number>;
  hasSelectedLimb: boolean;
  onAssignPart: (skinPart: SkinPartId | null) => void;
  onAssignOrder: (order: number | null) => void;
  onSelectPart: (partIndex: number, additive: boolean) => void;
}

export function AssignmentPanel({
  parts,
  assignments,
  orders,
  selectedParts,
  hasSelectedLimb,
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
      <p><strong>좌우 기준:</strong> 캐릭터를 뒤에서 바라본 방향입니다. 정면에서는 왼쪽과 오른쪽이 반대로 보입니다.</p>
      <p>박스를 클릭하면 같은 위치의 조각도 함께 선택됩니다.</p>
      <div className="assignment-buttons">
        {SKIN_PARTS.map((part) => (
          <button type="button" key={part.id} disabled={!hasSelection} onClick={() => onAssignPart(part.id)}>
            <i style={{ backgroundColor: part.color }} />{part.label}
          </button>
        ))}
        <button type="button" disabled={!hasSelection} onClick={() => onAssignPart(null)}>미지정</button>
      </div>
      <p><strong>팔다리 스킨 순서:</strong> 몸통에 가까운 조각은 0, 손·발에 가까운 조각은 1로 지정합니다.</p>
      <div className="assignment-buttons">
        <button type="button" disabled={!hasSelectedLimb} onClick={() => onAssignOrder(0)}>위쪽 0</button>
        <button type="button" disabled={!hasSelectedLimb} onClick={() => onAssignOrder(1)}>아래쪽 1</button>
        <button type="button" disabled={!hasSelectedLimb} onClick={() => onAssignOrder(null)}>자동</button>
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
  const label = SKIN_PARTS.find((part) => part.id === assignment)?.label ?? "미지정";
  return assignment && isLimbPart(assignment) && order != null ? `${label} · ${order}` : label;
}
