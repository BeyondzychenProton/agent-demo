import { useEffect, useRef, type ReactNode } from "react";
import { X } from "lucide-react";

export function SkillDialog({ title, children, footer, onClose, busy = false }: {
  title: string; children: ReactNode; footer?: ReactNode; onClose: () => void; busy?: boolean;
}) {
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement;
    panel.current?.focus();
    return () => { if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, []);
  return <div className="cap-modal-overlay" onMouseDown={event => { if (event.target === event.currentTarget && !busy) onClose(); }}>
    <div ref={panel} className="cap-modal cap-modal-wide" role="dialog" aria-modal="true" aria-label={title} tabIndex={-1}
      onKeyDown={event => {
        if (event.key === "Escape" && !busy) onClose();
        if (event.key !== "Tab") return;
        const elements = panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href]');
        if (!elements?.length) { event.preventDefault(); return; }
        const first = elements[0], last = elements[elements.length - 1];
        if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.current)) { event.preventDefault(); first.focus(); }
      }}>
      <div className="cap-modal-head"><h2>{title}</h2><button type="button" aria-label="关闭弹窗" disabled={busy} onClick={onClose}><X size={17}/></button></div>
      <div className="cap-modal-body">{children}</div>
      {footer && <div className="cap-modal-actions">{footer}</div>}
    </div>
  </div>;
}
