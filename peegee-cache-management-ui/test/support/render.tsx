import { render, screen, type RenderResult } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import type { ReactElement, ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';

import { ManagementProvider, type ManagementStore } from '@src/store';

/**
 * Renders a page inside the real router, Redux store, and client context, with antd motion
 * disabled: jsdom never fires transition/animation end events, so an animated Modal would stay
 * at opacity 0 and jest-dom would report its contents as not visible. Disabling motion is an
 * antd design-token configuration (`theme.token.motion`), not a test double. `virtual={false}`
 * mirrors the production shell's ConfigProvider so every Select option carries role="option".
 * The providers are installed as the Testing Library `wrapper`, so `rerender(...)` keeps them.
 */
export function renderWithProviders(ui: ReactElement, options: { readonly store: ManagementStore; readonly initialEntries?: string[] }): RenderResult {
  const Providers = ({ children }: { readonly children: ReactNode }) => (
    <MemoryRouter initialEntries={options.initialEntries}>
      <ConfigProvider theme={{ token: { motion: false } }} virtual={false}>
        <ManagementProvider store={options.store}>{children}</ManagementProvider>
      </ConfigProvider>
    </MemoryRouter>
  );
  return render(ui, { wrapper: Providers });
}

/**
 * Chooses an option in an antd `Select`/`ValueSelect` the way an operator does: open the
 * combobox, then click the option by its visible label.
 */
export async function chooseOption(user: { click: (element: HTMLElement) => Promise<void> }, combobox: HTMLElement, optionLabel: string): Promise<void> {
  await user.click(combobox);
  const option = await screen.findByRole('option', { name: optionLabel });
  await user.click(option);
}
