import type { NextConfig } from 'next';

const gateway = process.env.GATEWAY_API_URL || 'http://127.0.0.1:8080';
const nextConfig: NextConfig = {
  agentRules: false,
  devIndicators: false,
  async rewrites() {
    return [
      { source: '/admin/api/:path*', destination: `${gateway}/admin/api/:path*` },
      { source: '/admin/auth/:path*', destination: `${gateway}/admin/auth/:path*` },
      { source: '/oauth2/:path*', destination: `${gateway}/oauth2/:path*` },
      { source: '/login/oauth2/:path*', destination: `${gateway}/login/oauth2/:path*` },
    ];
  },
};

export default nextConfig;
