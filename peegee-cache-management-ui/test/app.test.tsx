import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from '@src/app/App';

describe('U0 production shell', () => {
  it('identifies the packaged management console without claiming product features', () => {
    render(<App />);

    expect(screen.getByRole('heading', { name: 'PeeGeeQ Cache Management' })).toBeVisible();
    expect(screen.getByText('Frontend foundation ready')).toBeVisible();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
});
