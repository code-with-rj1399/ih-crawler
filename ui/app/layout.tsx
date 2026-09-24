import './globals.css';
import type { Metadata } from 'next';

export const metadata: Metadata = { title: 'InterviewHQ Admin', description: 'Crawler administration' };
export default function RootLayout({children}:{children:React.ReactNode}) { return <html lang="en"><body>{children}</body></html>; }
