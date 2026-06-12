/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  experimental: {
    // Enable if needed for module federation in Stage 2
  },
};

module.exports = nextConfig;
