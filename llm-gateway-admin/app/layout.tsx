import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'LLM Gateway · 운영 콘솔',
  description: '요청과 비용, 적재 상태를 한곳에서 확인하는 운영 콘솔',
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ko"><body>{children}</body></html>;
}
