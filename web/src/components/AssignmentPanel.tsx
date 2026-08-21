import type { TargetedEvent, TargetedMouseEvent } from "preact";
import { useEffect, useRef } from "preact/hooks";
import type { NodeSpace } from "../format/emoteAnimation";
import type { PreviewPart } from "../preview/previewModel";
import {
  SKIN_PARTS,
  type PartAssignments,
  type PartOrders,
  type SkinPartId,
} from "../preview/skinParts";

interface AssignmentPanelProps {
  parts: PreviewPart[];
  assignments: PartAssignments;
  orders: PartOrders;
  spaces: Readonly<Record<string, NodeSpace>>;
  selectedParts: ReadonlySet<string>;
  hasSelectedAssignment: boolean;
  onAssignPart: (skinPart: SkinPartId | null) => void;
  onAssignOrder: (order: number) => void;
  onAssignSpace: (space: NodeSpace) => void;
  onSelectPart: (nodeId: string, additive: boolean) => void;
}

export function AssignmentPanel({
  parts,
  assignments,
  orders,
  spaces,
  selectedParts,
  hasSelectedAssignment,
  onAssignPart,
  onAssignOrder,
  onAssignSpace,
  onSelectPart,
}: AssignmentPanelProps) {
  const hasSelection = selectedParts.size > 0;
  const hasSelectedSkinPart = parts.some((part) => selectedParts.has(part.nodeId));
  const selectableItems = parts.map((part) => ({ nodeId: part.nodeId, label: `#${part.partIndex}`, detail: assignmentLabel(assignments[part.nodeId], orders[part.nodeId]) }));
  const partItems = useRef(new Map<string, HTMLLIElement>());
  const partList = useRef<HTMLUListElement>(null);
  const selectedOrders = [...selectedParts]
    .filter((nodeId) => assignments[nodeId] != null && orders[nodeId] != null)
    .map((nodeId) => orders[nodeId]!);
  const selectedOrder = selectedOrders.length > 0 && selectedOrders.every((order) => order === selectedOrders[0])
    ? String(selectedOrders[0])
    : "";

  useEffect(() => {
    const list = partList.current;
    const selectedItems = selectableItems
      .filter((item) => selectedParts.has(item.nodeId))
      .map((item) => partItems.current.get(item.nodeId))
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

  function handlePartClick(event: TargetedMouseEvent<HTMLButtonElement>, nodeId: string) {
    onSelectPart(nodeId, event.ctrlKey || event.metaKey || event.shiftKey);
  }

  function assignOrderAndScroll(order: number) {
    onAssignOrder(order);
    const lastSelectedPosition = parts.reduce(
      (last, part, index) => selectedParts.has(part.nodeId) ? index : last,
      -1,
    );
    const nextPart = parts[lastSelectedPosition + 1];
    if (nextPart) {
      requestAnimationFrame(() => {
        const list = partList.current;
        const item = partItems.current.get(nextPart.nodeId);
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
      <p><strong>Coordinate space</strong></p>
      <div className="assignment-buttons">
        {(["scene", "initiator", "partner"] as const).map((space) => (
          <button type="button" key={space} disabled={!hasSelection} onClick={() => onAssignSpace(space)}>
            {space[0].toUpperCase() + space.slice(1)}
          </button>
        ))}
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
            key={item.nodeId}
            ref={(element) => {
              if (element) partItems.current.set(item.nodeId, element);
              else partItems.current.delete(item.nodeId);
            }}
          >
            <button
              type="button"
              className={selectedParts.has(item.nodeId) ? "selected" : ""}
              onClick={(event) => handlePartClick(event, item.nodeId)}
            >
              <span>{item.label}</span>
              <span>{spaces[item.nodeId] ?? "scene"} · {item.detail}</span>
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
