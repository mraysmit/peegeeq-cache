import { Button, Result } from 'antd';
import { Component, type ReactNode } from 'react';

type ErrorBoundaryProps = { children: ReactNode };
type ErrorBoundaryState = { failed: boolean };

/** Last-resort boundary (reference: `components/common/ErrorBoundary`) rendered with antd `Result`. */
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
            <Result
              extra={<Button onClick={() => window.location.reload()} type="primary">Reload console</Button>}
              status="error"
              subTitle="Reload the console. If the problem continues, use the correlation details from the failed request."
              title={<h1>Console rendering failed</h1>}
            />
          </section>
        </main>
      );
    }
    return this.props.children;
  }
}
