'use client';

import React from 'react';

interface Props {
  children: React.ReactNode;
  onError?: (error: Error, info: React.ErrorInfo | null) => void;
  fallback?: React.ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class ErrorBoundary extends React.Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error('[ErrorBoundary]', error, info);
    this.props.onError?.(error, info);
  }

  render() {
    if (!this.state.hasError) return this.props.children;

    if (this.props.fallback) return this.props.fallback;

    return (
      <div className="hx-flex hx-flex-col hx-items-center hx-justify-center hx-p-8 hx-gap-4">
        <div className="hx-text-red-500 hx-text-4xl">⚠</div>
        <h2 className="hx-text-lg hx-font-semibold hx-text-gray-800 dark:hx-text-gray-100">
          Something went wrong
        </h2>
        <p className="hx-text-sm hx-text-gray-500 dark:hx-text-gray-400 hx-max-w-sm hx-text-center">
          {this.state.error?.message ?? 'An unexpected error occurred.'}
        </p>
        <button
          className="hx-px-4 hx-py-2 hx-text-sm hx-bg-blue-600 hx-text-white hx-rounded hover:hx-bg-blue-700"
          onClick={() => this.setState({ hasError: false, error: null })}
        >
          Try again
        </button>
      </div>
    );
  }
}
