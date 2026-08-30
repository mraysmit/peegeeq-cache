import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { EntryValueFormatter } from '@src/features/entries/EntryValueFormatter';

describe('U4 safe entry value formatters', () => {
  it('renders string content as text and escaped text without creating markup', async () => {
    const user = userEvent.setup();
    const hostile = '<img src=x onerror="window.pwned=true">\nline';
    const { container } = render(<EntryValueFormatter value={{ type: 'STRING', text: hostile }} />);

    expect(container.querySelector('.value-content')?.textContent).toBe(hostile);
    expect(container.querySelector('img')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Escaped text' }));
    expect(screen.getByText('<img src=x onerror=\\"window.pwned=true\\">\\nline')).toBeVisible();
    expect(container.querySelector('img')).toBeNull();
  });

  it('offers parsed, formatted, and raw JSON views with validation feedback', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<EntryValueFormatter value={{ type: 'JSON', text: '{"name":"<script>alert(1)</script>","count":2}' }} />);

    expect(screen.getByText('Valid JSON')).toBeVisible();
    expect(screen.getByText('<script>alert(1)</script>')).toBeVisible();
    expect(document.querySelector('script')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Formatted text' }));
    expect(screen.getByText(/"count": 2/u)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Raw UTF-8' }));
    expect(screen.getByText(/"name":"<script>/u)).toBeVisible();

    rerender(<EntryValueFormatter value={{ type: 'JSON', text: '{broken' }} />);
    expect(screen.getByText('Invalid JSON')).toBeVisible();
    expect(screen.getByText('{broken')).toBeVisible();
  });

  it('preserves long precision and formats bytes as hex, Base64, and UTF-8 when valid', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<EntryValueFormatter value={{ type: 'LONG', decimal: '9007199254740993123456789' }} />);
    expect(screen.getByText('9007199254740993123456789')).toBeVisible();

    rerender(<EntryValueFormatter value={{ type: 'BYTES', base64: 'SGVsbG8g8J+UkQ==' }} />);
    expect(screen.getByText('10 bytes')).toBeVisible();
    expect(screen.getByText('48 65 6c 6c 6f 20 f0 9f 94 91')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Base64' }));
    expect(screen.getByText('SGVsbG8g8J+UkQ==')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'UTF-8 attempt' }));
    expect(screen.getByText('Hello 🔑')).toBeVisible();

    rerender(<EntryValueFormatter value={{ type: 'BYTES', base64: '//4=' }} />);
    await user.click(screen.getByRole('button', { name: 'UTF-8 attempt' }));
    expect(screen.getByText('Not valid UTF-8')).toBeVisible();
  });
});
