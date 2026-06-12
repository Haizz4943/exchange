/** @type {import('tailwindcss').Config} */
module.exports = {
  prefix: 'hx-',
  content: ['./src/**/*.{ts,tsx}'],
  corePlugins: {
    // Disable preflight in library builds to avoid resetting host styles.
    // For standalone Next.js we re-enable it via globals.css @tailwind base.
    preflight: true,
  },
  theme: {
    extend: {
      colors: {
        'hx-bg': 'var(--hx-bg, #ffffff)',
        'hx-surface': 'var(--hx-surface, #f5f5f5)',
        'hx-text': 'var(--hx-text, #111111)',
        'hx-primary': 'var(--hx-primary, #2962ff)',
        'hx-success': 'var(--hx-success, #26a69a)',
        'hx-danger': 'var(--hx-danger, #ef5350)',
      },
      animation: {
        'pulse-once': 'pulse 0.6s ease-in-out',
      },
    },
  },
  plugins: [],
};
