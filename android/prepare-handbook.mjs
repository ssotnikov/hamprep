import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [, , sourcePath, assetDirectory, scriptPath] = process.argv;
const input = readFileSync(sourcePath, 'utf8');
const scriptPattern = /<script>\s*([\s\S]*?)<\/script>/g;
const scripts = [...input.matchAll(scriptPattern)];
if (scripts.length !== 1) {
  throw new Error('Expected one executable script in the handbook');
}

const mobileStyle = readFileSync(new URL('./handbook-mobile.css', import.meta.url), 'utf8');
const navigation = `<nav class="app-nav" aria-label="Навигация по справочнику">
  <a href="#topics">Темы</a>
  <a href="#study-practice">Вопросы</a>
  <a href="#mock-exam">Экзамен</a>
</nav>`;
const handbook = input
  .replace(/^\s*<link[^>]+href="https:\/\/fonts\.(?:googleapis|gstatic)\.com[^"]*"[^>]*>/gm, '')
  .replace('<nav class="mobile-toc"', '<nav id="topics" class="mobile-toc"')
  .replace('</head>', `<style>\n${mobileStyle}</style>\n</head>`)
  .replace('</body>', `${navigation}\n</body>`)
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
