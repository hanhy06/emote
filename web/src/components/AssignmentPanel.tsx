import { useEffect, useRef, type ChangeEvent, type MouseEvent } from "react";
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
  const partItems = useRef(new Map<number, HTMLLIElement>());
  const partList = useRef<HTMLUListElement>(null);
  const selectedOrders = [...selectedParts]
    .filter((index) => assignments[index] != null && orders[index] != null)
    .map((index) => orders[index]!);
  const selectedOrder = selectedOrders.length > 0 && selectedOrders.every((order) => order === selectedOrders[0])
    ? String(selectedOrders[0])
    : "";

  useEffect(() => {
    const list = partList.current;
    const selectedItems = parts
      .filter((part) => selectedParts.has(part.partIndex))
      .map((part) => partItems.current.get(part.partIndex))
      .filter((item): item is HTMLLIElement => item != null);
    if (!list || selectedItems.length === 0) return;
    const firstItem = selectedItems[0];
    const lastItem = selectedItems[selectedItems.length - 1];
    const visibleTop = list.scrollTop;
    const visibleBottom = visibleTop + list.clientHeight;
    if (firstItem.offsetTop < visibleTop) {
      list.scrollTo({ top: firstItem.offsetTop, behavior: "smooth" });
    } else if (lastItem.offsetTop + lastItem.offsetHeight > visibleBottom) {
      list.scrollTo({
        top: lastItem.offsetTop + lastItem.offsetHeight - list.clientHeight,
        behavior: "smooth",
      });
    }
  }, [parts, selectedParts]);

  function handlePartClick(event: MouseEvent<HTMLButtonElement>, partIndex: number) {
    onSelectPart(partIndex, event.ctrlKey || event.metaKey || event.shiftKey);
  }

  function assignOrderAndScroll(order: number) {
    onAssignOrder(order);
    const lastSelectedPosition = parts.reduce(
      (last, part, index) => selectedParts.has(part.partIndex) ? index : last,
      -1,
    );
    const nextPart = parts[lastSelectedPosition + 1];
    if (nextPart) {
      requestAnimationFrame(() => {
        const list = partList.current;
        const item = partItems.current.get(nextPart.partIndex);
        if (!list || !item) return;
        const itemBottom = item.offsetTop + item.offsetHeight;
        if (itemBottom > list.scrollTop + list.clientHeight) {
          list.scrollTo({ top: itemBottom - list.clientHeight, behavior: "smooth" });
        } else if (item.offsetTop < list.scrollTop) {
          list.scrollTo({ top: item.offsetTop, behavior: "smooth" });
        }
      });
    }
  }

  function handleOrderChange(event: ChangeEvent<HTMLInputElement>) {
    const order = Number(event.target.value);
    if (Number.isInteger(order)) assignOrderAndScroll(order);
  }

  return (
    <aside>
      <p><strong>Left/right reference:</strong> Directions are shown from behind the character. They appear reversed from the front.</p>
      <p>Click a model to select only that model. Hold Ctrl, Shift, or Command to select multiple models.</p>
      <div className="assignment-buttons">
        {SKIN_PARTS.map((part) => (
          <button type="button" key={part.id} disabled={!hasSelection} onClick={() => onAssignPart(part.id)}>
            <i style={{ backgroundColor: part.color }} />{part.label}
          </button>
        ))}
        <button type="button" disabled={!hasSelection} onClick={() => onAssignPart(null)}>Unassigned</button>
      </div>
      <label className="order-control">
        <strong>Skin order</strong>
        <input
          type="range"
          min="0"
          max="9"
          step="1"
          value={selectedOrder || "0"}
          disabled={!hasSelectedAssignment}
          onChange={handleOrderChange}
        />
        <output>{selectedOrder || "0"}</output>
      </label>
      <ul className="part-list" ref={partList}>
        {parts.map((part) => (
          <li
            key={part.partIndex}
            ref={(element) => {
              if (element) partItems.current.set(part.partIndex, element);
              else partItems.current.delete(part.partIndex);
            }}
          >
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
