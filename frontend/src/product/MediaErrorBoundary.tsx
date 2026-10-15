import { Component } from 'react';
import type { ReactNode } from 'react';

// A missing lazy chunk must not remove the product, photos or memory-only cart.
export default class MediaErrorBoundary extends Component<{ children: ReactNode; fallback: ReactNode; onFailure?: () => void }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch() { this.props.onFailure?.(); }
  render() { return this.state.failed ? this.props.fallback : this.props.children; }
}
