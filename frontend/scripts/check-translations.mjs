/**
 * `npm run i18n`: after `ng extract-i18n`, compares the French file with the extracted
 * English messages.
 *
 * The build already refuses a missing translation (i18nMissingTranslation: error). This
 * also catches what the build accepts silently: a translation that dropped or renamed a
 * placeholder, and translations left behind by English text that has since changed.
 */
import { readFileSync } from 'node:fs';

const read = (name) => JSON.parse(readFileSync(new URL(`../src/locale/${name}`, import.meta.url), 'utf8')).translations;
const source = read('messages.json');
const french = read('messages.fr.json');

const placeholders = (text) => (text.match(/\{\$[A-Za-z0-9_]+\}|\{(?:VAR_[A-Z]+|INTERPOLATION\w*)[,}]/g) ?? []).sort().join(' ');

const missing = Object.keys(source).filter((id) => !(id in french));
const unused = Object.keys(french).filter((id) => !(id in source));
const mismatched = Object.keys(source).filter((id) => id in french && placeholders(source[id]) !== placeholders(french[id]));

for (const id of missing) console.log(`missing    ${id}  ${source[id].replace(/\s+/g, ' ').trim().slice(0, 90)}`);
for (const id of mismatched) console.log(`placeholder ${id}  ${placeholders(source[id])}  ≠  ${placeholders(french[id])}`);
for (const id of unused) console.log(`unused     ${id}  ${french[id].replace(/\s+/g, ' ').trim().slice(0, 90)}`);
console.log(`${Object.keys(source).length} messages: ${missing.length} missing, ${mismatched.length} with other placeholders, ${unused.length} unused.`);
process.exit(missing.length || mismatched.length ? 1 : 0);
