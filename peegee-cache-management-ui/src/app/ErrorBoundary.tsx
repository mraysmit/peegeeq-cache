import { Component, type ReactNode } from 'react';

type ErrorBoundaryProps = { children: ReactNode };
type ErrorBoundaryState = { failed: boolean };

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  override state: ErrorBoundaryState = { failed: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { failed: true };
  }

  override render() {
    if (this.state.failed) {
      return (
        <main className="session-gate">
          <section className="session-card" role="alert">
            <p className="console__eyebrow">PeeGeeQ Cache</p>
            <h1>Console rendering failed</h1>
            <p>Reload the console. If the problem continues, use the correlation details from the failed request.</p>
            <button onClick={() => window.location.reload()} type="button">Reload console</button>
          </section>
        </main>
      );
    }
    return this.props.children;
  }
}
