import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Keep an explicit local API target while preserving variables passed by admin.sh.
const file = new URL('../.env.local', import.meta.url);
const line = readFileSync(file, 'utf8').split(/\r?\n/)
  .find(value => value.startsWith('GATEWAY_API_URL='));
if (!line) throw new Error('Missing GATEWAY_API_URL in llm-gateway-admin/.env.local');
process.env.GATEWAY_API_URL ||= line.slice('GATEWAY_API_URL='.length).trim();

process.argv = [process.argv[0], fileURLToPath(new URL('../node_modules/next/dist/bin/next', import.meta.url)),
  'dev', '--hostname', '127.0.0.1', ...process.argv.slice(2)];
await import('next/dist/bin/next');
