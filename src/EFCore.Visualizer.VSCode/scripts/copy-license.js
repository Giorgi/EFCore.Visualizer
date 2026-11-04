// The packaged extension carries the repository's license. vsce takes it from LICENSE next to package.json,
// so it's copied here before packaging (and ignored by git: the repository root has the original).
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
fs.copyFileSync(path.resolve(root, '..', '..', 'LICENSE'), path.join(root, 'LICENSE'));
console.log('LICENSE copied from the repository root');
