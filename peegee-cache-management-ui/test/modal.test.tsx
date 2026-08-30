import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { Modal } from '@src/components/Modal';

function Harness() {
  const [open, setOpen] = useState(false);
  return <>
    <button onClick={() => setOpen(true)} type="button">Open action</button>
    {open && <Modal labelId="test-dialog-title" onDismiss={() => setOpen(false)}>
      <h2 id="test-dialog-title">Confirm action</h2>
      <input aria-label="Confirmation" />
      <button onClick={() => setOpen(false)} type="button">Cancel</button>
    </Modal>}
  </>;
}

describe('accessible modal focus lifecycle', () => {
  it('enters the dialog, traps Tab in both directions, dismisses with Escape, and restores focus', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const trigger = screen.getByRole('button', { name: 'Open action' });

    await user.click(trigger);
    const confirmation = screen.getByRole('textbox', { name: 'Confirmation' });
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(confirmation).toHaveFocus();

    await user.tab();
    expect(cancel).toHaveFocus();
    await user.tab();
    expect(confirmation).toHaveFocus();
    await user.tab({ shift: true });
    expect(cancel).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
});
