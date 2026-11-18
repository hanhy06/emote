import { useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import type { SkinIR } from "../domain/animationIR";
import { createSkinOverride, decodeMask, decodeSkinPng, MAX_SKIN_PNG_BYTES } from "../domain/skin";

type SkinFile = keyof SkinIR | "mask";

interface SkinOptionsProps {
  skin?: SkinIR;
  disabled: boolean;
  onChange: (skin: SkinIR | undefined) => void;
}

async function readSkinPng(file: File): Promise<string> {
  if (file.size > MAX_SKIN_PNG_BYTES) throw new Error("Skin PNG must not exceed 1 MiB.");
  const bytes = new Uint8Array(await file.arrayBuffer());
  return btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(""));
}

export function SkinOptions({ skin, disabled, onChange }: SkinOptionsProps) {
  const [upload, setUpload] = useState<"reading" | { kind: SkinFile; data: string } | null>(null);
  const [selectedMask, setSelectedMask] = useState<string>();
  const [error, setError] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);
  const fileKind = useRef<SkinFile>("default");
  const unavailable = disabled || upload !== null;
  const override = skin?.override;
  const explicitMask = useMemo(() => {
    if (!override || selectedMask === override.mask) return selectedMask;
    try {
      return createSkinOverride(override.texture).mask === override.mask ? undefined : override.mask;
    } catch {
      return override.mask;
    }
  }, [override, selectedMask]);

  useLayoutEffect(() => {
    if (!upload || upload === "reading") return;
    setUpload(null);
    try {
      const { kind, data } = upload;
      if (kind === "mask") {
        if (override) onChange({ ...skin, override: createSkinOverride(override.texture, data) });
        else decodeMask(data);
        setSelectedMask(data);
      } else if (kind === "default") {
        decodeSkinPng(data);
        onChange({ ...skin, default: data });
      } else {
        onChange({ ...skin, override: createSkinOverride(data, explicitMask) });
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Could not read the skin PNG.");
    }
  }, [upload, skin, override, explicitMask, onChange]);

  async function selectFile(input: HTMLInputElement) {
    const file = input.files?.[0];
    input.value = "";
    if (!file) return;
    const kind = fileKind.current;
    setError("");
    setUpload("reading");
    try {
      setUpload({ kind, data: await readSkinPng(file) });
    } catch (reason) {
      setUpload(null);
      setError(reason instanceof Error ? reason.message : "Could not read the skin PNG.");
    }
  }

  function skinControl(image: string | undefined, kind: SkinFile, onRemove: () => void) {
    const hasImage = image !== undefined;
    const label = kind === "mask" ? "mask" : `${kind} skin`;
    return (
      <button type="button" disabled={unavailable} className={hasImage ? "skin-thumbnail" : undefined}
        aria-label={`${hasImage ? "Remove" : "Add"} ${label}`}
        onClick={() => {
          setError("");
          if (hasImage) onRemove();
          else {
            fileKind.current = kind;
            fileInput.current?.click();
          }
        }}>
        {hasImage ? <>
          <img src={`data:image/png;base64,${image}`} width={96} height={96} alt="" />
          <span className="skin-thumbnail-remove" aria-hidden="true">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v5m4-5v5" />
            </svg>
          </span>
        </> : kind === "mask" ? "Add mask" : <>Add {kind}<br />skin</>}
      </button>
    );
  }

  return (
    <section className="skin-options" aria-labelledby="skin-options-heading">
      <h3 id="skin-options-heading">Skin</h3>
      <div className="skin-actions">
        {skinControl(skin?.default, "default", () => onChange(override ? { override } : undefined))}
        {skinControl(override?.texture, "override", () => {
          setSelectedMask(explicitMask);
          onChange(skin?.default !== undefined ? { default: skin.default } : undefined);
        })}
        {skinControl(explicitMask, "mask", () => {
          try {
            if (override) onChange({ ...skin, override: createSkinOverride(override.texture) });
            setSelectedMask(undefined);
          } catch (reason) {
            setError(reason instanceof Error ? reason.message : "Could not generate the mask.");
          }
        })}
      </div>
      <input type="file" accept=".png,image/png" hidden ref={fileInput} onChange={event => void selectFile(event.currentTarget)} />
      {upload === "reading" && <p className="field-hint" role="status">Reading PNG…</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </section>
  );
}
