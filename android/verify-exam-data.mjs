import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Verified against the question list under Order No. 224 of 2015-10-01.
const QUESTION_DIGEST = '6567a9d681eefd92def15683542809e3bf316a67cc43b775d43cc1423d75b9b1';
const FIGURE_DIGEST = '46feb6a9f8b283e123cb948df7d32acadac06ce522b585c333546a64ac3b7907';
const NOVICE_RANGES = [[47, 98], [100, 135], [150, 226], [387, 391], [409, 422]];
const OFFICIAL_RANGES = [
  [[1, 426]],
  [[1, 38], [47, 98], [100, 374], [387, 426]],
  [[1, 34], ...NOVICE_RANGES],
  [[1, 17], ...NOVICE_RANGES]
];

function jsonBlock(html, id) {
  const match = html.match(new RegExp(`<script type="application/json" id="${id}">([\\s\\S]*?)</script>`));
  assert.ok(match, `Missing data block: ${id}`);
  return JSON.parse(match[1]);
}

function digest(value) {
  return createHash('sha256').update(JSON.stringify(value)).digest('hex');
}

function matchingNumbers(questions, ranges) {
  return questions.filter(({ no }) => ranges.some(([first, last]) => first <= no && no <= last))
    .map(({ no }) => no);
}

function verifyCategoryRanges(html, questions) {
  const noviceRanges = html.match(/const NOVICE_RANGES = (\[[^\r\n;]+\]);/);
  const categoryBlock = html.match(/const CATEGORIES = \{([\s\S]*?)\n\s*\};/);
  assert.ok(noviceRanges && categoryBlock, 'Missing exam category definitions');
  const definitions = [...categoryBlock[1].matchAll(/^\s*([1-4]): \{[^\r\n]*ranges: (\[[^\r\n]+\]) \},?$/gm)];
  assert.deepEqual(definitions.map(([, category]) => category), ['1', '2', '3', '4']);

  for (const [, category, rawRanges] of definitions) {
    const ranges = JSON.parse(rawRanges.replace('...NOVICE_RANGES', noviceRanges[1].slice(1, -1)));
    assert.deepEqual(matchingNumbers(questions, ranges), matchingNumbers(questions, OFFICIAL_RANGES[category - 1]),
      `Incorrect question membership for category ${category}`);
  }
}

export function verifyExamData(html) {
  const questions = jsonBlock(html, 'pdf-bank-data');
  const figures = jsonBlock(html, 'pdf-bank-figures');
  assert.equal(questions.length, 426, 'Expected 426 exam questions');

  const usedFigures = new Set();
  for (const [index, question] of questions.entries()) {
    const number = index + 1;
    assert.equal(question.no, number, 'Question numbers must be consecutive');
    assert.equal(question.id, `pdf_${number}`, `Invalid question ID at ${number}`);
    assert.ok(typeof question.stem === 'string' && question.stem.trim(), `Empty question ${number}`);
    assert.ok(Array.isArray(question.options) && question.options.length === 4
      && question.options.every(option => typeof option === 'string' && option.trim()),
    `Expected four text options at question ${number}`);
    assert.ok(Number.isInteger(question.correct) && question.correct >= 0 && question.correct < 4,
      `Invalid answer key at question ${number}`);
    if (question.figure !== undefined) {
      assert.ok(Object.hasOwn(figures, question.figure), `Missing figure for question ${number}`);
      usedFigures.add(question.figure);
    }
  }
  assert.deepEqual([...usedFigures].sort(), Object.keys(figures).sort(), 'Unexpected unused exam figures');
  verifyCategoryRanges(html, questions);
  assert.equal(digest(questions.map(({ no, page, stem, options, correct, figure }) =>
    [no, page, stem, options, correct, figure ?? null])), QUESTION_DIGEST,
  'Exam questions, answer key or figure references differ from the verified source');
  assert.equal(digest(figures), FIGURE_DIGEST, 'Exam figures differ from the verified source');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const source = process.argv[2] ?? new URL('../html/ham_prepare_ru.html', import.meta.url);
  verifyExamData(readFileSync(source, 'utf8'));
  console.log('Exam data verified against Order No. 224');
}
