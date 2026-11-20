import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { verifyExamData } from './verify-exam-data.mjs';

const [, , sourcePath, assetDirectory, scriptPath, version] = process.argv;
const input = readFileSync(sourcePath, 'utf8');
verifyExamData(input);
const versionLabel = /(<span id="app-version">)[^<]+(<\/span>)/;
if (!/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.+-]+)?$/.test(version) || !versionLabel.test(input)) {
  throw new Error('Expected a version label and a valid manifest version');
}
const scriptPattern = /<script>\s*([\s\S]*?)<\/script>/g;
const scripts = [...input.matchAll(scriptPattern)];
if (scripts.length !== 1) {
  throw new Error('Expected one executable script in the handbook');
}

const mobileStyle = readFileSync(new URL('./handbook-mobile.css', import.meta.url), 'utf8');
const handbook = input
  .replace(versionLabel, (_label, tagStart, tagEnd) => tagStart + version + tagEnd)
  .replace(/^\s*<link[^>]+href="https:\/\/fonts\.(?:googleapis|gstatic)\.com[^"]*"[^>]*>/gm, '')
  .replace('</head>', `<style>\n${mobileStyle}</style>\n</head>`)
  .replace(scriptPattern, '<script src="handbook.js"></script>');

const unexpectedLocalFiles = [...handbook.matchAll(/\bhref="([^"#?:]+\.[^"?#]+)(?:#[^"]*)?"/gi)];
if (unexpectedLocalFiles.length > 0) {
  throw new Error(`Unbundled local references: ${unexpectedLocalFiles.map(match => match[1]).join(', ')}`);
}
writeFileSync(join(assetDirectory, 'ham_prepare_ru.html'), handbook);

// Chromium 69 has no String.replaceAll. These searches all use literal characters.
const script = scripts[0][1]
  .replaceAll(".replaceAll('&', '&amp;')", ".replace(/&/g, '&amp;')")
  .replaceAll(".replaceAll('<', '&lt;')", ".replace(/</g, '&lt;')")
  .replaceAll(".replaceAll('>', '&gt;')", ".replace(/>/g, '&gt;')")
  .replaceAll(".replaceAll('ё', 'е')", ".replace(/ё/g, 'е')");
writeFileSync(scriptPath, script);
