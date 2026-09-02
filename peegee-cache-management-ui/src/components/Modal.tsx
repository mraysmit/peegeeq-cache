import { useLayoutEffect, useRef, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react';

interface ModalProps {
  readonly children: ReactNode;
  readonly className?: string;
  readonly labelId: string;
  readonly onDismiss?: () => void;
}

const focusableSelector = [
  'button:not([disabled])',
  '[href]',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/** Accessible modal boundary with deterministic focus entry, containment, dismissal, and restoration. */
export function Modal({ children, className = 'modal modal--compact', labelId, onDismiss }: ModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : undefined;
    const dialog = dialogRef.current;
    const first = dialog?.querySelector<HTMLElement>('[data-autofocus], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])');
    (first ?? dialog)?.focus();
    return () => previouslyFocused?.focus();
  }, []);

  useLayoutEffect(() => {
    if (onDismiss === undefined) return;
    const dismissTopmostModal = (event: globalThis.KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      const dialogs = document.querySelectorAll<HTMLElement>('[role="dialog"][aria-modal="true"]');
      if (dialogs.item(dialogs.length - 1) !== dialogRef.current) return;
      event.preventDefault();
      event.stopPropagation();
      onDismiss();
    };
    document.addEventListener('keydown', dismissTopmostModal);
    return () => document.removeEventListener('keydown', dismissTopmostModal);
  }, [onDismiss]);

  const handleKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Tab') return;
    const focusable = [...(dialogRef.current?.querySelectorAll<HTMLElement>(focusableSelector) ?? [])];
    if (focusable.length === 0) {
      event.preventDefault();
      dialogRef.current?.focus();
      return;
    }
    const first = focusable[0]!;
    const last = focusable.at(-1)!;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return <div className="modal-backdrop">
    <div
      aria-labelledby={labelId}
      aria-modal="true"
      className={className}
      onKeyDown={handleKeyDown}
      ref={dialogRef}
      role="dialog"
      tabIndex={-1}
    >
      {children}
    </div>
  </div>;
}
