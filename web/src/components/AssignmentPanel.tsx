import type { TargetedEvent } from "preact";
import { useEffect, useRef } from "preact/hooks";
import type { PreviewPart } from "../preview/previewModel";
import type { PlayerSkinPart } from "../domain/player";
import {
  SKIN_PARTS,
  type PartAssignments,
  type PartOrders,
} from "../preview/skinParts";

interface AssignmentPanelProps {
  parts: Pick<PreviewPart, "candidateId" | "partIndex">[];
  assignments: PartAssignments;
  orders: PartOrders;
  selectedCandidateIds: ReadonlySet<string>;
  onAssignPart: (skinPart: PlayerSkinPart | null) => void;
  onAssignOrder: (order: number) => void;
  onSelectCandidate: (candidateId: string, additive: boolean) => void;
}

export function AssignmentPanel({
  parts,
  assignments,
  orders,
  selectedCandidateIds,
  onAssignPart,
  onAssignOrder,
  onSelectCandidate,
}: AssignmentPanelProps) {
  const hasSelectedSkinPart = parts.some((part) => selectedCandidateIds.has(part.candidateId));
  const hasSelectedAssignment = [...selectedCandidateIds].some((candidateId) => assignments[candidateId] != null);
  const selectableItems = parts.map((part) => ({ candidateId: part.candidateId, label: `#${part.partIndex}`, detail: assignmentLabel(assignments[part.candidateId], orders[part.candidateId]) }));
  const partItems = useRef(new Map<string, HTMLLIElement>());
  const partList = useRef<HTMLUListElement>(null);
  const selectedOrders = [...selectedCandidateIds]
    .filter((candidateId) => assignments[candidateId] != null && orders[candidateId] != null)
    .map((candidateId) => orders[candidateId]!);
  const selectedOrder = selectedOrders.length > 0 && selectedOrders.every((order) => order === selectedOrders[0])
    ? String(selectedOrders[0])
    : "";

  useEffect(() => {
    const list = partList.current;
    const selectedItems = selectableItems
      .filter((item) => selectedCandidateIds.has(item.candidateId))
      .map((item) => partItems.current.get(item.candidateId))
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
  }, [parts, selectedCandidateIds]);

  function assignOrderAndScroll(order: number) {
    onAssignOrder(order);
    const lastSelectedPosition = parts.reduce(
      (last, part, index) => selectedCandidateIds.has(part.candidateId) ? index : last,
      -1,
    );
    const nextPart = parts[lastSelectedPosition + 1];
    if (nextPart) {
      requestAnimationFrame(() => {
        const list = partList.current;
        const item = partItems.current.get(nextPart.candidateId);
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

  function handleOrderChange(event: TargetedEvent<HTMLInputElement>) {
    const order = Number(event.currentTarget.value);
    if (Number.isInteger(order)) assignOrderAndScroll(order);
  }

  return (
    <aside className="assignment-panel">
      <p><strong>Left/right reference:</strong> Directions are shown from behind the character. They appear reversed from the front.</p>
      <p>Click a model to select it, or click it again to clear the selection. Hold Ctrl, Shift, or Command to select multiple models. Hold Ctrl and drag in the preview to select a range.</p>
      <p><strong>Skin</strong></p>
      <div className="assignment-buttons">
        {SKIN_PARTS.map((part) => (
          <button type="button" key={part.id} disabled={!hasSelectedSkinPart} onClick={() => onAssignPart(part.id)}>
            <i style={{ backgroundColor: part.color }} />{part.label}
          </button>
        ))}
        <button type="button" disabled={!hasSelectedSkinPart} onClick={() => onAssignPart(null)}>Unassigned</button>
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
        {selectableItems.map((item) => (
          <li
            key={item.candidateId}
            ref={(element) => {
              if (element) partItems.current.set(item.candidateId, element);
              else partItems.current.delete(item.candidateId);
            }}
          >
            <button
              type="button"
              className={selectedCandidateIds.has(item.candidateId) ? "selected" : ""}
              onClick={(event) => onSelectCandidate(item.candidateId, event.ctrlKey || event.metaKey || event.shiftKey)}
            >
              <span>{item.label}</span>
              <span>{item.detail}</span>
            </button>
          </li>
        ))}
      </ul>
    </aside>
  );
}

function assignmentLabel(assignment: PlayerSkinPart | null | undefined, order: number | null | undefined): string {
  const label = SKIN_PARTS.find((part) => part.id === assignment)?.label ?? "Unassigned";
  return assignment && order != null ? `${label} · ${order}` : label;
}
