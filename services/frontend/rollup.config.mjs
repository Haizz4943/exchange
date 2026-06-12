/**
 * Library bundle config — Stage 2 npm package build.
 * Target: dist/index.js (ESM), dist/index.cjs.js (CJS), dist/styles.css
 *
 * NOT used for the standalone Next.js app (npm run build uses next build).
 * Install additional devDeps before using: rollup, @rollup/plugin-typescript,
 * rollup-plugin-postcss, @rollup/plugin-node-resolve, @rollup/plugin-commonjs.
 */

// export default {
//   input: 'src/panel/HaizzTradingPanel.tsx',
//   output: [
//     { file: 'dist/index.js', format: 'es', sourcemap: true },
//     { file: 'dist/index.cjs.js', format: 'cjs', sourcemap: true },
//   ],
//   external: ['react', 'react-dom', 'next/dynamic'],
//   plugins: [
//     typescript({ tsconfig: './tsconfig.lib.json' }),
//     postcss({ extract: 'styles.css', modules: false }),
//     resolve(),
//     commonjs(),
//   ],
// };

export default {};
