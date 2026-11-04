// Writes src/EFCore.Visualizer.Core/Resources/ThirdPartyNotices.txt from the license files of the vendored libraries
// and everything they depend on. Both extensions ship that folder, so they ship the notices too.
//
//   cd tools/third-party-notices
//   npm ci --ignore-scripts   # downloads the packages; runs none of their scripts
//   node generate.js
//
// After updating a vendored library, update its version in package.json and in VENDORED below, run
// "npm install --ignore-scripts" instead of "npm ci", then generate.js.
const fs = require('fs');
const path = require('path');

const VENDORED = `The query plan pages include these files from third-party libraries:

  Postgres/pev2.umd.js         pev2 1.25.0 (PostgreSQL License). It bundles its dependencies, listed below
                               with the others: d3, d3-flextree, Font Awesome, highlight.js, humanize-duration,
                               lodash, motion, splitpanes, tippy.js, vue-tippy, vue-clipboard3, @vueuse/core.
  Postgres/vue.global.prod.js  Vue 3.5.44 (MIT)
  Postgres/bootstrap.min.css   Bootstrap 5.3.8 (MIT)
  SqlServer/qp.min.js, qp.css, qp_icons.png
                               html-query-plan (MIT), which includes svg.js 2.6.2 (MIT)
  Common/highlight.min.js, highlight.sql.js, highlight.css, highlight.dark.min.css
                               highlight.js 11.11.1 (BSD-3-Clause)
  Common/treeflex.css          Treeflex (MIT)

The license texts below are taken from the packages npm installs for these versions. For pev2's dependencies,
those are the newest versions its version ranges allow, which can differ slightly from the ones bundled into
pev2.umd.js.`;

const root = __dirname;
const output = path.resolve(root, '..', '..', 'src', 'EFCore.Visualizer.Core', 'Resources', 'ThirdPartyNotices.txt');

/** Every installed package (also nested node_modules), by name. */
function packages(dir, found = new Map()) {
  for (const entry of fs.existsSync(dir) ? fs.readdirSync(dir) : []) {
    if (entry.startsWith('.')) continue;
    const full = path.join(dir, entry);
    if (entry.startsWith('@')) {
      packages(full, found);
      continue;
    }
    const manifest = path.join(full, 'package.json');
    if (!fs.existsSync(manifest)) continue;
    const pkg = JSON.parse(fs.readFileSync(manifest, 'utf8'));
    // Type definitions aren't shipped.
    if (!pkg.name.startsWith('@types/') && !found.has(pkg.name)) {
      found.set(pkg.name, { name: pkg.name, license: typeof pkg.license === 'string' ? pkg.license : pkg.license?.type, dir: full });
    }
    packages(path.join(full, 'node_modules'), found);
  }
  return found;
}

function licenseText(dir) {
  const file = fs.readdirSync(dir).find((f) => /^(licen[cs]e|copying)(\.(md|txt))?$/i.test(f));
  return file ? fs.readFileSync(path.join(dir, file), 'utf8').replace(/\r\n/g, '\n').trim() : undefined;
}

const all = [...packages(path.join(root, 'node_modules')).values()];
// Compiled into html-query-plan's qp.min.js, so it isn't an npm dependency.
all.push({ name: 'svg.js', license: 'MIT', text: fs.readFileSync(path.join(root, 'svg.js-LICENSE.txt'), 'utf8').replace(/\r\n/g, '\n').trim() });
all.sort((a, b) => a.name.localeCompare(b.name));

const missing = [];
const sections = all.map((p) => {
  const text = p.text ?? licenseText(p.dir);
  if (!text) missing.push(p.name);
  return `${'-'.repeat(100)}\n${p.name}\nLicense: ${p.license ?? 'see below'}\n\n${text ?? `(The package has no license file. Its package.json says: ${p.license}.)`}`;
});

fs.writeFileSync(output, `THIRD-PARTY NOTICES\n\n${VENDORED}\n\n${sections.join('\n\n')}\n`.replace(/\n/g, '\r\n'));
console.log(`${all.length} packages written to ${output}`);
if (missing.length) console.log(`No license file in: ${missing.join(', ')}`);
