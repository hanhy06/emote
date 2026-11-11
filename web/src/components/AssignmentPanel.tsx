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

  function revealPart(id: string) {
    const item = partItems.current.get(id);
    const panel = partList.current?.closest(".inspector");
    if (!item?.getClientRects().length || !panel) return;
    const bounds = item.getBoundingClientRect();
    const viewport = panel.getBoundingClientRect();
    if (bounds.top < viewport.top) panel.scrollTop += bounds.top - viewport.top;
    else if (bounds.bottom > viewport.bottom) panel.scrollTop += bounds.bottom - viewport.bottom;
  }

  useEffect(() => {
    const last = [...selectedCandidateIds].at(-1);
    if (last) revealPart(last);
  }, [selectedCandidateIds]);

  function assignOrderAndScroll(order: number) {
    onAssignOrder(order);
    const last = parts.reduce((index, part, i) => selectedCandidateIds.has(part.candidateId) ? i : index, -1);
    const next = parts[last + 1];
    if (next) requestAnimationFrame(() => revealPart(next.candidateId));
  }

  function handleOrderChange(event: TargetedEvent<HTMLInputElement>) {
    const order = Number(event.currentTarget.value);
    if (Number.isInteger(order)) assignOrderAndScroll(order);
  }

  return (
    <details className="property-section" open>
      <summary><strong>Skin parts</strong><small>{selectedCandidateIds.size ? `${selectedCandidateIds.size} selected` : `${parts.filter(part => assignments[part.candidateId]).length} / ${parts.length} assigned`}</small></summary>
      <div className="property-body">
        <p className="field-hint">Select parts in the preview or list. Ctrl, Shift, or ⌘ adds to the selection. Ctrl-drag selects an area. Left and right are the character’s own.</p>
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
        <details className="part-picker"><summary>Model parts ({parts.length})</summary>
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
        </details>
      </div>
    </details>
  );
}

function assignmentLabel(assignment: PlayerSkinPart | null | undefined, order: number | null | undefined): string {
  const label = SKIN_PARTS.find((part) => part.id === assignment)?.label ?? "Unassigned";
  return assignment && order != null ? `${label} · ${order}` : label;
}
